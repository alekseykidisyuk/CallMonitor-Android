#!/usr/bin/env python3
"""Build a first-install cPanel ZIP; setup key is generated locally per package.

Generated archive/staging files and setup key MUST NOT be committed to git.
Production device/admin credentials are generated only by the host installer.
"""
import argparse, hashlib, json, pathlib, secrets, shutil, tempfile, zipfile
SRC=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(); p.add_argument('--output',required=True); args=p.parse_args()
out=pathlib.Path(args.output).resolve(); out.parent.mkdir(parents=True,exist_ok=True)
root=pathlib.Path(tempfile.mkdtemp(prefix='cm-package-'))
try:
    private=root/'callmonitor_private'; public=root/'callmonitor.sensera.online'
    private.mkdir(); public.mkdir()
    for part in ('app','bin'): shutil.copytree(SRC/'private'/part,private/part)
    templates=private/'templates'; templates.mkdir()
    for name in ('index.php','.htaccess','.user.ini'): shutil.copy(SRC/'public'/name,templates/name)
    shutil.copy(SRC/'public/callmonitor_install_v010.php',public)
    shutil.copytree(SRC/'docs',private/'docs')
    windows=private/'windows'; windows.mkdir()
    for name in ('CallMonitor_SMOKE_v010.ps1','RUN_SMOKE.cmd','alternate.ogg.b64'): shutil.copy(SRC/'tools'/name,windows/name)
    # UTF-8 BOM is needed for reliable Windows PowerShell 5.1 script decoding.
    ps=windows/'CallMonitor_SMOKE_v010.ps1'; ps.write_text(ps.read_text(encoding='utf-8'),encoding='utf-8-sig')
    cfg=private/'config'; cfg.mkdir()
    key=secrets.token_urlsafe(32)
    (cfg/'setup.php').write_text("<?php\nreturn ['setup_key_hash'=>'"+hashlib.sha256(key.encode()).hexdigest()+"'];\n")
    (root/'CallMonitor_INSTALL_README.txt').write_text(
        'CallMonitor Server v0.1.0 — ПЕРВАЯ установка\n\n'
        'Уникальный код установки (не отправлять в чат):\n'+key+'\n\n'
        '1. cPanel → File Manager → /home1/sensera\n'
        '2. Загрузите ZIP и распакуйте в /home1/sensera.\n'
        '3. После проверки распаковки откройте:\n'
        '   https://callmonitor.sensera.online/callmonitor_install_v010.php\n'
        '4. Введите код выше, название компании и имя менеджера.\n'
        '5. Сохраните ответ с admin_token и device_token ЛОКАЛЬНО. Не отправляйте токены в чат.\n'
        '6. После успеха удалите публичный установщик, ZIP и этот README с сервера.\n\n'
        'Подробная инструкция: callmonitor_private/docs/INSTALL_RU.md\n'
        'Проверка с Windows: callmonitor_private/windows/RUN_SMOKE.cmd\n\n'
        'Не распаковывать повторно поверх установленного CallMonitor.\n'
        'Пакет не изменяет Android APK, FarmBase или DataHub.\n',encoding='utf-8-sig')
    hashes={str(f.relative_to(root)):hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(root.rglob('*')) if f.is_file()}
    (private/'docs/PACKAGE_SHA256.json').write_text(json.dumps(hashes,indent=2),encoding='utf-8')
    with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
        for f in sorted(root.rglob('*')):
            rel=f.relative_to(root).as_posix()
            zi=zipfile.ZipInfo(rel+('/' if f.is_dir() else ''))
            zi.create_system=3
            if f.is_dir(): zi.external_attr=(0o40700 if rel.startswith('callmonitor_private') else 0o40755)<<16
            else: zi.external_attr=(0o100600 if rel.startswith('callmonitor_private') else 0o100644)<<16
            zi.compress_type=zipfile.ZIP_DEFLATED
            z.writestr(zi,b'' if f.is_dir() else f.read_bytes())
    with zipfile.ZipFile(out) as z:
        assert z.testzip() is None
        assert set(n for n in z.namelist() if n.startswith('callmonitor.sensera.online/') and not n.endswith('/'))=={'callmonitor.sensera.online/callmonitor_install_v010.php'}
        manifest=json.loads(z.read('callmonitor_private/docs/PACKAGE_SHA256.json'))
        assert all(hashlib.sha256(z.read(n)).hexdigest()==h for n,h in manifest.items())
    print(json.dumps({'file':str(out),'bytes':out.stat().st_size,'sha256':hashlib.sha256(out.read_bytes()).hexdigest(),'manifest_verified':True}))
finally: shutil.rmtree(root)
