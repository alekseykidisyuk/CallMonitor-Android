<?php
declare(strict_types=1);
if(PHP_SAPI!=='cli') { http_response_code(404); exit; }
require dirname(__DIR__).'/app/core.php';
// CLI/cPanel Terminal only. Tokens are printed once, never put in command args.
try {
    $db=db(); $cmd=$argv[1] ?? 'help';
    $id=$argv[2] ?? '';
    if(in_array($cmd,['add-tenant','add-device','disable-device','enable-device','rotate-device'],true) && !preg_match('/^[a-z0-9][a-z0-9_-]{0,63}$/D',$id)) throw new RuntimeException('Invalid ID');
    if($cmd==='status') echo json_encode(status_data(),JSON_PRETTY_PRINT|JSON_UNESCAPED_UNICODE)."\n";
    elseif($cmd==='add-tenant') query($db,'INSERT INTO tenants VALUES(?,?,1,?)',[$id,$argv[3] ?? $id,utc()]);
    elseif($cmd==='add-device') {
        $t=$argv[3] ?? ''; $token=token();
        query($db,'INSERT INTO devices(device_id,tenant_id,token_hash,model,created_at) VALUES(?,?,?,?,?)',[$id,$t,hash('sha256',$token),$argv[4] ?? 'Unverified device; verify L/R before use',utc()]);
        echo json_encode(['device_id'=>$id,'tenant_id'=>$t,'device_token'=>$token])."\n";
    } elseif(in_array($cmd,['disable-device','enable-device'],true)) {
        $s=query($db,'UPDATE devices SET enabled=? WHERE device_id=?',[$cmd==='enable-device'?1:0,$id]);
        if(!$s->rowCount()) throw new RuntimeException('Device not found');
    } elseif($cmd==='rotate-device') {
        $token=token(); $s=query($db,'UPDATE devices SET token_hash=? WHERE device_id=?',[hash('sha256',$token),$id]);
        if(!$s->rowCount()) throw new RuntimeException('Device not found');
        echo json_encode(['device_id'=>$id,'device_token'=>$token])."\n";
    } elseif($cmd==='rotate-admin') {
        $token=token(); $c=cfg(); $c['admin_token_hash']=hash('sha256',$token);
        atomic_text(private_root().'/config/config.php',"<?php\nreturn ".var_export($c,true).";\n");
        echo json_encode(['admin_token'=>$token])."\n";
    } elseif($cmd==='backup-db') {
        mkdir_private(private_root().'/backups');
        $path=private_root().'/backups/callmonitor-'.gmdate('Ymd-His').'-'.bin2hex(random_bytes(4)).'.sqlite3';
        $db->exec('VACUUM INTO '.$db->quote($path)); echo $path."\n";
    } else echo "Commands: status | add-tenant ID NAME | add-device ID TENANT MODEL | disable-device ID | enable-device ID | rotate-device ID | rotate-admin | backup-db\n";
} catch(Throwable $e) { fwrite(STDERR,"Operation failed (check IDs, database and permissions).\n"); exit(1); }
