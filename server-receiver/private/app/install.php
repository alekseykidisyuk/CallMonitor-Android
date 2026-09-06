<?php
declare(strict_types=1);
require_once __DIR__.'/core.php';
function install_page(): never {
    security_headers();
    try {
        secure_transport();
        $private=private_root(); $public=realpath(dirname($private).'/callmonitor.sensera.online');
        if(!$public || $public!==realpath($_SERVER['DOCUMENT_ROOT']) || is_link($private) || realpath($private)!==$private || str_starts_with($private.'/', $public.'/')) fail(409,'unsafe_install_paths');
        if(is_file($private.'/config/config.php')) fail(409,'already_installed_remove_installer');
        foreach(['pdo_sqlite','openssl','fileinfo','json'] as $ext) if(!extension_loaded($ext)) fail(503,'missing_extension_'.$ext);
        if(PHP_VERSION_ID<80300 || PHP_INT_SIZE<8) fail(503,'php83_64bit_required');
        if(!is_file($private.'/config/setup.php')) fail(503,'setup_key_missing');
        if(($_SERVER['REQUEST_METHOD'] ?? '')!=='POST') {
            header('Content-Type: text/html; charset=utf-8');
            echo '<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Установка CallMonitor</title><style>body{font:17px system-ui;max-width:700px;margin:40px auto;padding:20px}input{font:inherit;width:100%;box-sizing:border-box;padding:10px}button{font:inherit;margin-top:20px;padding:12px}</style><h1>CallMonitor Server 0.1.0</h1><p>Создание закрытого хранилища, базы и первого устройства.</p><form method="post"><label>Код установки из CallMonitor_INSTALL_README.txt<input type="password" name="setup_key" required autocomplete="off"></label><p><label>Название компании<input name="tenant_name" value="CallMonitor — пилот" required maxlength="120"></label></p><label>Имя менеджера<input name="operator_name" value="Алексей" required maxlength="120"></label><button>Установить сервер</button></form></html>'; exit;
        }
        $key=field('setup_key',160); $tenantName=field('tenant_name',240); $operatorName=field('operator_name',240);
        if(trim($tenantName)==='' || trim($operatorName)==='') fail(400,'names_required');
        $lock=fopen($private.'/config/install.lock','c+b'); if(!$lock || !flock($lock,LOCK_EX)) fail(503,'install_lock_failed');
        try {
            if(is_file($private.'/config/config.php')) fail(409,'already_installed_remove_installer');
            $state=json_decode(stream_get_contents($lock) ?: '{}',true) ?: [];
            if(time()-($state['since'] ?? 0)>900) $state=['since'=>time(),'attempts'=>0];
            if(($state['attempts'] ?? 0)>=20) fail(429,'setup_retry_after_15_minutes');
            $setup=require $private.'/config/setup.php';
            if(!hash_equals($setup['setup_key_hash'],hash('sha256',$key))) {
                $state['attempts']=($state['attempts'] ?? 0)+1; rewind($lock); ftruncate($lock,0); fwrite($lock,json_encode($state)); fflush($lock);
                fail(401,'invalid_setup_key');
            }
            // Refuse to overwrite unrelated public files; partial installs may resume
            // only when each existing public file exactly matches this package.
            foreach(['index.php','.htaccess','.user.ini'] as $name) {
                $target=$public.'/'.$name; $source=$private.'/templates/'.$name;
                if(is_link($target) || (is_file($target) && !hash_equals(hash_file('sha256',$source),hash_file('sha256',$target)))) fail(409,'existing_public_file_'.str_replace('.','_',$name));
            }
            foreach(['config','data','audio','logs','tmp'] as $dir) { mkdir_private($private.'/'.$dir); if(!chmod($private.'/'.$dir,0700)) fail(503,'private_permissions_failed'); }
            if(!chmod($private,0700)) fail(503,'private_permissions_failed');
            $targetDb=$private.'/data/callmonitor.sqlite3';
            if(is_file($targetDb)) {
                // Only possible before final config commit. Preserve interrupted DB,
                // never silently delete it; generated tokens from that attempt were
                // not returned before successful completion.
                if(!rename($targetDb,$targetDb.'.interrupted-'.bin2hex(random_bytes(5)))) fail(503,'partial_install_backup_failed');
            }
            $tempDb=$targetDb.'.'.bin2hex(random_bytes(6)).'.tmp';
            $db=new PDO('sqlite:'.$tempDb,null,null,[PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION]);
            $db->exec(file_get_contents($private.'/app/schema.sql'));
            $adminToken=token(); $deviceToken=token(); $now=utc();
            $db->beginTransaction();
            query($db,'INSERT INTO tenants VALUES(?,?,1,?)',['pilot',$tenantName,$now]);
            query($db,'INSERT INTO operators VALUES(?,?,?)',['operator-01','pilot',$operatorName]);
            query($db,'INSERT INTO devices(device_id,tenant_id,operator_id,token_hash,model,created_at) VALUES(?,?,?,?,?,?)',['redmi-note12-01','pilot','operator-01',hash('sha256',$deviceToken),'Xiaomi Redmi Note 12 / build 19 capture profile',$now]);
            $db->commit();
            if($db->query('PRAGMA integrity_check')->fetchColumn()!=='ok') fail(503,'database_check_failed');
            $db=null;
            if(!rename($tempDb,$targetDb)) fail(503,'database_install_failed');
            chmod($targetDb,0600);
            foreach(['index.php','.htaccess','.user.ini'] as $name) {
                if(!is_file($public.'/'.$name)) atomic_text($public.'/'.$name,file_get_contents($private.'/templates/'.$name));
                chmod($public.'/'.$name,0644);
            }
            $config=['version'=>CM_VERSION,'base_url'=>'https://callmonitor.sensera.online','admin_token_hash'=>hash('sha256',$adminToken),'max_audio_bytes'=>100*1024*1024,'installed_at'=>$now];
            atomic_text($private.'/config/config.php',"<?php\nreturn ".var_export($config,true).";\n");
            @unlink($private.'/config/setup.php');
            audit('installed',201);
            json_response(['ok'=>true,'status'=>'installed','version'=>CM_VERSION,'tenant_id'=>'pilot','operator_id'=>'operator-01','device_id'=>'redmi-note12-01','admin_token'=>$adminToken,'device_token'=>$deviceToken,'health_url'=>'https://callmonitor.sensera.online/health.php','status_url'=>'https://callmonitor.sensera.online/status.php','next'=>'Save both tokens locally. Do not send them in chat. Delete public callmonitor_install_v010.php.'],201);
        } finally { flock($lock,LOCK_UN); fclose($lock); }
    } catch(ApiError $e) { json_response(['ok'=>false,'error'=>$e->reason],$e->status); }
      catch(Throwable $e) { audit('install_failed',503); json_response(['ok'=>false,'error'=>'install_failed_check_php_error_log'],503); }
}
