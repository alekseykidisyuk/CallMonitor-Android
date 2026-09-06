<?php
declare(strict_types=1);
// Generated self-contained, admin-authenticated v0.1.0 -> v0.1.1 update.
// Writes only the four embedded allowlisted files; no SQL mutations.
ini_set('display_errors','0');
$private=dirname(__DIR__).'/callmonitor_private';
if(!is_file($private.'/app/core.php')) { http_response_code(503); exit('CallMonitor core not found.'); }
require $private.'/app/core.php';
$manifest = /* PAYLOAD_MANIFEST */ [];
security_headers();
try {
    secure_transport();
    if(realpath(__DIR__)!==realpath($_SERVER['DOCUMENT_ROOT']) || is_link($private) || realpath($private)!==private_root()) fail(409,'unsafe_update_paths');
    if(($_SERVER['REQUEST_METHOD'] ?? '')!=='POST') {
        header('Content-Type: text/html; charset=utf-8');
        echo '<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Обновление CallMonitor 0.1.1</title><style>body{font:17px system-ui;max-width:700px;margin:40px auto;padding:20px}input{font:inherit;width:100%;box-sizing:border-box;padding:10px}button{font:inherit;margin-top:20px;padding:12px}</style><h1>CallMonitor 0.1.1</h1><p>Совместимость с проверенной записью Redmi без метки EOS. Сохранение оригинального OGG, проверок целостности и существующих токенов.</p><form method="post"><label>Токен администратора — admin_token<input type="password" name="token" required autocomplete="off" maxlength="160"></label><button>Применить обновление</button></form></html>'; exit;
    }
    admin_auth(field('token',160));
    $backup=$private.'/updates/R011_EOS'; mkdir_private($backup);
    $lock=fopen($backup.'/upgrade.lock','c+b'); if(!$lock || !flock($lock,LOCK_EX)) fail(503,'update_lock_failed');
    try {
        $device=query(db(),'SELECT tenant_id,device_id,channels,left_role,right_role FROM devices WHERE device_id=?',['redmi-note12-01'])->fetch();
        if(!$device || $device['tenant_id']!=='pilot' || (int)$device['channels']!==2 || $device['left_role']!=='operator' || $device['right_role']!=='client') fail(409,'pilot_device_profile_mismatch');
        if((int)db()->query('PRAGMA user_version')->fetchColumn()!==1) fail(409,'unexpected_schema');
        $changed=[]; $oldContent=[];
        foreach($manifest as $rel=>$item) {
            if(!in_array($rel,['config/capture_profiles.php','app/ogg.php','app/api.php','app/core.php'],true)) fail(500,'invalid_manifest_path');
            $path=$private.'/'.$rel;
            if(is_link($path) || realpath(dirname($path))!==$private.'/'.dirname($rel)) fail(409,'unsafe_target_path');
            $hash=is_file($path)?hash_file('sha256',$path):null;
            if($hash!==$item['before'] && $hash!==$item['after']) fail(409,'preflight_hash_mismatch_'.basename($rel));
            $body=base64_decode($item['content_b64'],true);
            if($body===false || hash('sha256',$body)!==$item['after']) fail(500,'invalid_payload');
            if($hash!==$item['after']) {
                $changed[$rel]=$body;
                $oldContent[$rel]=is_file($path)?file_get_contents($path):null;
            }
        }
        if(!$changed) json_response(['ok'=>true,'status'=>'already_installed','version'=>'0.1.1','database_modified'=>false,'schema_modified'=>false,'tokens_changed'=>false]);
        // Durable copies before any target change. Existing backups must match.
        foreach($oldContent as $rel=>$content) {
            if($content===null) continue;
            $dst=$backup.'/originals/'.$rel; mkdir_private(dirname($dst));
            if(is_file($dst) && hash_file('sha256',$dst)!==hash('sha256',$content)) fail(409,'backup_hash_mismatch');
            if(!is_file($dst)) atomic_text($dst,$content);
        }
        atomic_text($backup.'/manifest.json',json_encode(array_map(fn($item)=>['before'=>$item['before'],'after'=>$item['after']],$manifest),JSON_PRETTY_PRINT));
        $applied=[];
        try {
            // All transitions are backwards compatible. Each individual replace
            // is atomic. Interrupted updates can resume after hash verification.
            foreach($changed as $rel=>$content) {
                atomic_text($private.'/'.$rel,$content); $applied[]=$rel;
                if(function_exists('opcache_invalidate')) @opcache_invalidate($private.'/'.$rel,true);
            }
            foreach($manifest as $rel=>$item) if(hash_file('sha256',$private.'/'.$rel)!==$item['after']) throw new RuntimeException('postflight_failed');
        } catch(Throwable $e) {
            $rolledBack=true;
            foreach(array_reverse($applied) as $rel) {
                try {
                    if($oldContent[$rel]===null) {
                        if(!unlink($private.'/'.$rel)) throw new RuntimeException('rollback_delete_failed');
                    } else atomic_text($private.'/'.$rel,$oldContent[$rel]);
                    if(function_exists('opcache_invalidate')) @opcache_invalidate($private.'/'.$rel,true);
                } catch(Throwable $ignored) { $rolledBack=false; }
            }
            json_response(['ok'=>false,'error'=>'update_failed','rollback_complete'=>$rolledBack,'database_modified'=>false],503);
        }
        $receipt=['ok'=>true,'status'=>'updated','version'=>'0.1.1','changed_files'=>array_keys($changed),'database_modified'=>false,'schema_modified'=>false,'tokens_changed'=>false,'backup_dir'=>$backup,'updated_at'=>utc(),'next'=>'Delete this public updater, then rerun RUN_DIAG_D1.cmd with the same recording and device token.'];
        atomic_text($backup.'/result.json',json_encode($receipt,JSON_PRETTY_PRINT|JSON_UNESCAPED_SLASHES));
        audit('updated_to_0_1_1',200); json_response($receipt);
    } finally { flock($lock,LOCK_UN); fclose($lock); }
} catch(ApiError $e) { json_response(['ok'=>false,'error'=>$e->reason],$e->status); }
  catch(Throwable $e) { json_response(['ok'=>false,'error'=>'update_preparation_failed','database_modified'=>false],503); }
