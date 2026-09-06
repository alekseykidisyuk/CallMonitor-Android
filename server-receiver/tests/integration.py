#!/usr/bin/env python3
"""Black-box PHP HTTP acceptance tests; synthetic audio, no production secrets."""
import base64, concurrent.futures, hashlib, json, os, pathlib, shutil, signal, socket, sqlite3, subprocess, tempfile, time, urllib.error, urllib.parse, urllib.request, uuid, zipfile, sys

SRC=pathlib.Path(__file__).resolve().parents[1]
ROOT=pathlib.Path(tempfile.mkdtemp(prefix='cm-test-'))
PR=ROOT/'callmonitor_private'; PUB=ROOT/'callmonitor.sensera.online'
subprocess.run([sys.executable,str(SRC/'tools/build_package.py'),'--output',str(ROOT/'test.zip')],check=True)
with zipfile.ZipFile(ROOT/'test.zip') as package: package.extractall(ROOT)
# Install the exact deployed v0.1.0 bytes, then exercise the real updater.
for name in ('api.php','ogg.php','core.php'): shutil.copy(SRC/'tests/baseline_v010'/name,PR/'app'/name)
update=PUB/'callmonitor_update_011_eos.php'
subprocess.run([sys.executable,str(SRC/'updates/build_update.py'),'--output',str(update)],check=True)
KEY='test-only-'+uuid.uuid4().hex
(PR/'config/setup.php').write_text("<?php return ['setup_key_hash'=>'"+hashlib.sha256(KEY.encode()).hexdigest()+"'];")
router=ROOT/'router.php'
router.write_text("<?php $p=parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH); if($p==='/callmonitor_update_011_eos.php'){require __DIR__.'/callmonitor.sensera.online/callmonitor_update_011_eos.php';}elseif($p==='/callmonitor_install_v010.php'){require __DIR__.'/callmonitor.sensera.online/callmonitor_install_v010.php';}elseif(in_array($p,['/','/index.php','/health.php','/status.php','/api/v1/health','/api/v1/status','/api/v1/calls'],true)){require __DIR__.'/callmonitor.sensera.online/index.php';}else{http_response_code(404); echo 'not_found';}")
with socket.socket() as s: s.bind(('127.0.0.1',0)); PORT=s.getsockname()[1]
URL=f'http://127.0.0.1:{PORT}'
log=open(ROOT/'php.log','wb')
# Tests deliberately rewrite config/source between requests. Disable CLI opcode
# caching so these fixture edits do not depend on an image's revalidation delay.
proc=subprocess.Popen(['php','-d','opcache.enable_cli=0','-d','upload_max_filesize=101M','-d','post_max_size=102M','-S',f'127.0.0.1:{PORT}','-t',str(PUB),str(router)],env={**os.environ,'CM_TEST_HTTP':'1','PHP_CLI_SERVER_WORKERS':'4'},stdout=log,stderr=log,start_new_session=True)
checks=[]
def check(condition,name):
    assert condition,name
    checks.append(name); print('PASS',name,flush=True)
def req(path, data=None, token=None, content_type=None):
    headers={}
    if token: headers['Authorization']='Bearer '+token
    if content_type: headers['Content-Type']=content_type
    r=urllib.request.Request(URL+path,data=data,headers=headers)
    try:
        with urllib.request.urlopen(r,timeout=45) as f: code=f.status; raw=f.read()
    except urllib.error.HTTPError as e: code=e.code; raw=e.read()
    try: return code,json.loads(raw)
    except (ValueError,UnicodeError): return code,raw.decode(errors='replace')
def form(data): return urllib.parse.urlencode(data).encode()
def fixture(name):
    p=SRC/'tests'/name
    return p.read_bytes() if p.exists() else base64.b64decode((p.with_suffix(p.suffix+'.b64')).read_text())
A=fixture('stereo.ogg'); B=fixture('alternate.ogg'); MONO=fixture('mono.ogg')
def ogg_pages(audio):
    pages=[];pos=0
    while pos<len(audio):
        n=audio[pos+26];length=27+n+sum(audio[pos+27:pos+27+n]);pages.append(bytearray(audio[pos:pos+length]));pos+=length
    return pages
def fix_crc(page):
    page[22:26]=b'\0'*4;crc=0
    for byte in page:
        crc^=byte<<24
        for _ in range(8): crc=((crc<<1)^(0x04c11db7 if crc&0x80000000 else 0))&0xffffffff
    page[22:26]=crc.to_bytes(4,'little');return bytes(page)
pages=ogg_pages(A);pages[-1][5]&=~4
NO_EOS=b''.join(bytes(p) for p in pages[:-1])+fix_crc(pages[-1])
def meta(audio=A,**overrides):
    m=dict(call_id=str(uuid.uuid4()),device_id='redmi-note12-01',direction='out',remote_number='+998901234567',started_at=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),duration_ms='1000',app_build='19',audio_sha256=hashlib.sha256(audio).hexdigest(),audio_bytes=str(len(audio)),codec='opus',sample_rate='48000',channels='2',channel_layout='stereo',original_filename='test.ogg')
    m.update(overrides); return m
def upload(m,audio=A,auth=None,filename='test.ogg'):
    boundary='cm'+uuid.uuid4().hex; parts=[]
    for k,v in m.items(): parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
    parts.extend([f'--{boundary}\r\nContent-Disposition: form-data; name="audio"; filename="{filename}"\r\nContent-Type: audio/ogg\r\n\r\n'.encode(),audio,f'\r\n--{boundary}--\r\n'.encode()])
    return req('/api/v1/calls',b''.join(parts),auth or DEVICE,'multipart/form-data; boundary='+boundary)
def conn(): return sqlite3.connect(PR/'data/callmonitor.sqlite3',timeout=15)
def count(call_id):
    with conn() as db: return db.execute('SELECT COUNT(*) FROM calls WHERE tenant_id=? AND call_id=?',('pilot',call_id)).fetchone()[0]
def cli(*args): return subprocess.check_output(['php',str(PR/'bin/manage.php'),*args],text=True)

try:
    for _ in range(100):
        try: req('/callmonitor_install_v010.php'); break
        except urllib.error.URLError: time.sleep(.1)
    check(proc.poll() is None,'PHP HTTP server starts')
    c,_=req('/callmonitor_install_v010.php'); check(c==200,'installer form available')
    setup={'setup_key':KEY,'tenant_name':'Pilot test','operator_name':'Test operator'}
    c,r=req('/callmonitor_install_v010.php',form({**setup,'setup_key':'wrong'}),content_type='application/x-www-form-urlencoded'); check(c==401,'wrong setup key rejected')
    # Existing unrelated files must survive a failed installation attempt.
    (PUB/'index.php').write_text('UNRELATED')
    c,r=req('/callmonitor_install_v010.php',form(setup),content_type='application/x-www-form-urlencoded')
    check(c==409 and (PUB/'index.php').read_text()=='UNRELATED','installer refuses public file overwrite')
    (PUB/'index.php').unlink()
    c,r=req('/callmonitor_install_v010.php',form(setup),content_type='application/x-www-form-urlencoded')
    check(c==201 and r.get('status')=='installed','fresh installation succeeds')
    DEVICE=r['device_token']; ADMIN=r['admin_token']
    c,r=req('/callmonitor_install_v010.php',form(setup),content_type='application/x-www-form-urlencoded'); check(c==409,'second installation blocked')
    check(not (PR/'config/setup.php').exists(),'setup key removed after install')
    check((PR.stat().st_mode & 0o777)==0o700 and ((PR/'data/callmonitor.sqlite3').stat().st_mode & 0o777)==0o600,'private permissions enforced')
    c,r=req('/health.php'); check(c==200 and r['version']=='0.1.0','health ready')
    missing=meta(NO_EOS)
    c,r=upload(missing,audio=NO_EOS);check(c==400 and r['error']=='incomplete_ogg','deployed v0.1.0 reproduces missing-EOS rejection')
    c,r=upload(meta());check(c==201,'existing normal call stored before update')
    beforedb=(PR/'data/callmonitor.sqlite3').read_bytes();beforeconfig=(PR/'config/config.php').read_bytes()
    def upgrade(auth=ADMIN):return req('/callmonitor_update_011_eos.php',form({'token':auth}),content_type='application/x-www-form-urlencoded')
    c,r=upgrade(DEVICE);check(c==401,'device token cannot apply server update')
    oldogg=(PR/'app/ogg.php').read_bytes();(PR/'app/ogg.php').write_bytes(oldogg+b'\n// unrelated modification\n')
    c,r=upgrade();check(c==409 and not (PR/'config/capture_profiles.php').exists(),'updater refuses unexpected hashes before changing files')
    (PR/'app/ogg.php').write_bytes(oldogg)
    c,r=upgrade();check(c==200 and r['status']=='updated' and r['version']=='0.1.1','authenticated v0.1.1 updater succeeds')
    check(beforedb==(PR/'data/callmonitor.sqlite3').read_bytes() and beforeconfig==(PR/'config/config.php').read_bytes(),'update leaves DB and credential config byte-for-byte unchanged')
    for name in ('api.php','ogg.php','core.php'):
        check((PR/'updates/R011_EOS/originals/app'/name).read_bytes()==(SRC/'tests/baseline_v010'/name).read_bytes(),'backup matches original '+name)
    c,r=upgrade();check(c==200 and r['status']=='already_installed','repeated update is idempotent')
    # Resume a compatible partial transition using the preserved original hashes.
    shutil.copy(SRC/'tests/baseline_v010/api.php',PR/'app/api.php')
    c,r=upgrade();check(c==200 and r['changed_files']==['app/api.php'],'interrupted update resumes without replacing other files')
    c,r=req('/health.php');check(c==200 and r['version']=='0.1.1','health reports v0.1.1 after update')
    c,r=upload(missing,audio=NO_EOS);check(c==201 and r['audio_eos_present'] is False and r['warnings']==['ogg_eos_missing'],'profile-approved page-aligned EOF accepted with explicit warning')
    with conn() as db:
        row=db.execute('SELECT relative_path,processing_status FROM calls WHERE call_id=?',(missing['call_id'],)).fetchone()
    check((PR/'audio'/row[0]).read_bytes()==NO_EOS and row[1]=='received_eos_missing','missing-EOS original preserved without remux and flagged in DB')
    c,r=upload(missing,audio=NO_EOS);check(c==200 and r['duplicate'] and count(missing['call_id'])==1,'missing-EOS duplicate remains exactly one call')
    c,r=req('/api/v1/status',token=ADMIN);check(any(x['processing_status']=='received_eos_missing' for x in r['last_calls']),'admin status exposes missing-EOS marker')
    c,r=upload(meta(NO_EOS[:-1]),audio=NO_EOS[:-1]);check(c==400,'profile compatibility still rejects partial page')
    damaged=NO_EOS[:-1]+bytes([NO_EOS[-1]^1])
    c,r=upload(meta(damaged),audio=damaged);check(c==400 and r['error']=='ogg_crc_mismatch','profile compatibility still enforces CRC')
    last=ogg_pages(NO_EOS)[-1];head=bytearray(last[:27]);head[5]=0;head[6:14]=b'\xff'*8
    head[18:22]=(int.from_bytes(head[18:22],'little')+1).to_bytes(4,'little');head[26]=1
    incomplete=NO_EOS+fix_crc(head+b'\xff'+b'x'*255)
    c,r=upload(meta(incomplete),audio=incomplete);check(c==400 and r['error']=='incomplete_ogg','profile compatibility rejects unfinished packet even with valid page CRC')
    unknown=ogg_pages(NO_EOS);unknown[-1][6:14]=b'\xff'*8
    unknown=b''.join(bytes(p) for p in unknown[:-1])+fix_crc(unknown[-1])
    c,r=upload(meta(unknown),audio=unknown);check(c==400,'profile compatibility rejects unknown final granule')
    unknownDevice=json.loads(cli('add-device','unverified-device','pilot','test'))['device_token']
    c,r=upload(meta(NO_EOS,device_id='unverified-device',allow_page_aligned_eof='true'),audio=NO_EOS,auth=unknownDevice)
    check(c==400 and r['error']=='incomplete_ogg','client metadata cannot enable EOS compatibility on another device')
    c,r=upload(meta(B,call_id=missing['call_id']),audio=B);check(c==409,'missing-EOS call cannot be overwritten with different valid audio')
    c,_=req('/api/v1/status'); check(c==401,'unauthenticated status rejected')
    c,_=req('/api/v1/status',token=DEVICE); check(c==401,'device token cannot read admin status')
    c,_=upload(meta(),auth=ADMIN); check(c==401,'admin token cannot upload as device')
    c,_=upload(meta(),auth='x'*43); check(c==401,'invalid device token rejected')
    c,_=upload(meta(device_id='other')); check(c==403,'device identity spoof rejected')
    c,_=upload(meta(operator_id='other')); check(c==403,'operator identity spoof rejected')
    c,_=upload(meta(left_role='client')); check(c==400,'channel role spoof rejected')
    c,_=upload(meta(started_at='2026-02-30T10:00:00Z')); check(c==400,'invalid calendar date rejected')
    c,_=upload(meta(call_id='../../bad')); check(c==400,'path traversal identifier rejected')
    c,_=upload(meta(audio_sha256='0'*64)); check(c==400,'SHA mismatch rejected')
    c,_=upload(meta(audio_bytes=str(len(A)+1))); check(c==400,'size mismatch rejected')
    c,_=upload(meta(),filename='audio.php'); check(c==400,'executable extension rejected')
    c,_=upload(meta(MONO),audio=MONO); check(c==400,'mono disguised as stereo rejected')
    damaged=A[:-10]+bytes([A[-10]^1])+A[-9:]
    c,_=upload(meta(damaged),audio=damaged); check(c==400,'Ogg CRC corruption rejected despite matching SHA')
    c,_=upload(meta(A[:-1]),audio=A[:-1]); check(c==400,'truncated Ogg rejected')
    c,_=upload(meta(b'<?php echo 1;'),audio=b'<?php echo 1;'); check(c==400,'non-audio content rejected')
    m=meta(); c,r=upload(m); check(c==201 and r['stored'] and not r['duplicate'],'valid stereo upload accepted')
    sid=r['server_call_id']; first_at=r['received_at']
    c,r=upload(m); check(c==200 and r['duplicate'] and r['server_call_id']==sid and r['received_at']==first_at and count(m['call_id'])==1,'duplicate returns original row')
    c,r=upload(meta(B,call_id=m['call_id']),audio=B); check(c==409 and count(m['call_id'])==1,'same call different valid audio conflicts')
    c,_=upload(meta()); check(c==201,'same audio different call is allowed')
    with conn() as db:
        row=db.execute('SELECT relative_path,operator_id,audio_duration_ms FROM calls WHERE server_call_id=?',(sid,)).fetchone()
    dest=PR/'audio'/row[0]
    check(dest.read_bytes()==A and row[1]=='operator-01' and row[2]==1000,'stored audio bytes, authoritative operator and duration correct')
    c,_=req('/audio/'+row[0]); check(c==404,'private audio has no public route')
    check(not any(p.suffix in ('.ogg','.sqlite3') for p in PUB.rglob('*')),'audio and database physically outside document root')
    # Simulate process termination AFTER rename, BEFORE DB insert/commit.
    orphan=meta(); opath=PR/'audio/pilot/redmi-note12-01'/orphan['call_id'][:2]/(orphan['call_id']+'.ogg'); opath.parent.mkdir(parents=True,exist_ok=True); opath.write_bytes(A)
    c,r=upload(orphan); check(c==201 and count(orphan['call_id'])==1 and opath.read_bytes()==A,'rename-before-commit orphan adopted on retry')
    # A missing file is recoverable by exact retransmission; corrupt data is not overwritten.
    dest.unlink(); c,r=upload(m); check(c==200 and dest.read_bytes()==A and count(m['call_id'])==1,'missing committed file repaired by same bytes')
    dest.write_bytes(B); c,r=upload(m); check(c==503 and dest.read_bytes()==B,'corrupt stored file preserved and reported')
    dest.write_bytes(A)
    parallel=meta()
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool: rr=list(pool.map(lambda _:upload(parallel),range(8)))
    check(sum(c==201 for c,_ in rr)==1 and all(c in (200,201) for c,_ in rr) and count(parallel['call_id'])==1,'eight concurrent retries create exactly one row')
    cli('disable-device','redmi-note12-01'); c,_=upload(meta()); check(c==401,'disabled device rejected')
    cli('enable-device','redmi-note12-01')
    cli('add-tenant','second','Second company'); second=json.loads(cli('add-device','second-device','second','test'))['device_token']
    c,r=upload(meta(call_id=m['call_id'],device_id='second-device'),auth=second); check(c==201,'same UUID is isolated across tenants')
    third=json.loads(cli('add-device','third-device','pilot','test'))['device_token']
    c,_=upload(meta(call_id=m['call_id'],device_id='third-device'),auth=third); check(c==409,'other device cannot reuse existing call in tenant')
    old=DEVICE; DEVICE=json.loads(cli('rotate-device','redmi-note12-01'))['device_token']
    c,_=upload(meta(),auth=old); check(c==401,'old token revoked on rotation')
    c,_=upload(meta()); check(c==201,'new device token works')
    c,r=req('/api/v1/status',token=ADMIN); check(c==200 and r['calls_total']>=5 and all(x['remote_number']=='***4567' for x in r['last_calls']),'admin status masks numbers')
    c,r=req('/status.php',form({'token':ADMIN}),content_type='application/x-www-form-urlencoded'); check(c==200 and 'CallMonitor' in r and ADMIN not in r,'read-only status page renders without echoing token')
    rawdb=(PR/'data/callmonitor.sqlite3').read_bytes(); rawconfig=(PR/'config/config.php').read_bytes()
    rawlogs=b''.join(p.read_bytes() for p in (PR/'logs').glob('*'))
    check(all(t.encode() not in rawdb+rawconfig+rawlogs for t in [KEY,old,DEVICE,ADMIN,second,third]),'no plaintext credentials in DB/config/logs')
    check(b'+998901234567' not in rawlogs,'audit logs contain no full phone numbers')
    backup=pathlib.Path(cli('backup-db').strip())
    with sqlite3.connect(backup) as b: check(b.execute('PRAGMA integrity_check').fetchone()[0]=='ok','online database backup valid')
    # Test policy without allocating a 100 MiB request.
    config=PR/'config/config.php'; saved=config.read_text(); config.write_text(saved.replace(str(100*1024*1024),'1024'))
    c,r=upload(meta()); check(c==413,'configured file-size policy enforced (HTTP '+str(c)+')'); config.write_text(saved)
    # secure_transport must reject unencrypted production requests regardless of proxy header.
    php="require "+repr(str(PR/'app/core.php'))+"; $_SERVER['HTTP_X_FORWARDED_PROTO']='https'; try{secure_transport();exit(2);}catch(ApiError $e){exit($e->status===426?0:3);}"
    check(subprocess.run(['php','-r',php]).returncode==0,'untrusted forwarded header cannot bypass HTTPS requirement')
    check(not list((PR/'tmp').glob('*.part')),'temporary upload files cleaned')
    print(json.dumps({'ok':True,'checks':len(checks),'php':subprocess.check_output(['php','-r','echo PHP_VERSION;'],text=True),'tests':checks},ensure_ascii=False,indent=2))
    (SRC/'tests/RESULT.json').write_text(json.dumps({'ok':True,'checks':len(checks),'tests':checks},indent=2))
finally:
    os.killpg(proc.pid,signal.SIGTERM); proc.wait(timeout=10); log.close()
    # Test logs never include request body or bearer values.
    if proc.returncode and len(checks)<40: print((ROOT/'php.log').read_text()[-5000:])
    shutil.rmtree(ROOT,ignore_errors=True)
