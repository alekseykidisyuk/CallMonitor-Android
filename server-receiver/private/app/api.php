<?php
declare(strict_types=1);
require_once __DIR__.'/core.php';
require_once __DIR__.'/ogg.php';

function receive_call(PDO $db, array $device): array {
    $max=cfg()['max_audio_bytes'];
    if((int)($_SERVER['CONTENT_LENGTH'] ?? 0)>$max+1048576) fail(413,'request_too_large');
    if(!str_starts_with(strtolower($_SERVER['CONTENT_TYPE'] ?? ''),'multipart/form-data;')) fail(400,'multipart_required');
    $id=strtolower(field('call_id',36));
    if(!preg_match('/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/D',$id)) fail(400,'invalid_call_id');
    if(field('device_id',80)!==$device['device_id']) fail(403,'device_mismatch');
    $op=field('operator_id',80,true);
    if($op!==null && $op!=='' && $op!==$device['operator_id']) fail(403,'operator_mismatch');
    $direction=field('direction',3); if(!in_array($direction,['in','out'],true)) fail(400,'invalid_direction');
    $remote=field('remote_number',64); if($remote==='') $remote=null;
    if($remote!==null && !preg_match('/^[+0-9() .#*\-]{1,64}$/D',$remote)) fail(400,'invalid_remote_number');
    $start=field('started_at',40);
    if(!preg_match('/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})$/D',$start)) fail(400,'invalid_started_at');
    try { $dt=new DateTimeImmutable($start); } catch(Throwable $e) { fail(400,'invalid_started_at'); }
    $dateErrors=DateTimeImmutable::getLastErrors();
    if($dateErrors!==false && ($dateErrors['warning_count'] || $dateErrors['error_count'])) fail(400,'invalid_started_at');
    if($dt->getTimestamp()<946684800 || $dt->getTimestamp()>time()+86400) fail(400,'invalid_started_at');
    $duration=integer_field('duration_ms',1,86400000);
    $build=integer_field('app_build',1,2147483647);
    $bytes=integer_field('audio_bytes',1,$max);
    $sha=strtolower(field('audio_sha256',64)); if(!preg_match('/^[a-f0-9]{64}$/D',$sha)) fail(400,'invalid_audio_sha256');
    if(field('codec',10)!=='opus' || field('channel_layout',10)!=='stereo' || integer_field('channels',1,2)!==2 || integer_field('sample_rate',1,192000)!==48000) fail(400,'unsupported_audio_profile');
    foreach(['left_role','right_role'] as $role) { $v=field($role,30,true); if($v!==null && $v!==$device[$role]) fail(400,'channel_role_mismatch'); }
    $name=field('original_filename',240,true);
    $f=$_FILES['audio'] ?? null;
    if(!is_array($f) || !isset($f['error']) || !is_int($f['error'])) fail(400,'audio_required');
    if(in_array($f['error'],[UPLOAD_ERR_INI_SIZE,UPLOAD_ERR_FORM_SIZE],true)) fail(413,'audio_too_large');
    if($f['error']!==UPLOAD_ERR_OK) fail(in_array($f['error'],[UPLOAD_ERR_NO_TMP_DIR,UPLOAD_ERR_CANT_WRITE,UPLOAD_ERR_EXTENSION],true)?503:400,'upload_incomplete');
    if(!is_string($f['tmp_name']) || !is_uploaded_file($f['tmp_name'])) fail(400,'invalid_upload');
    if(!is_string($f['name']) || strtolower(pathinfo($f['name'],PATHINFO_EXTENSION))!=='ogg') fail(400,'ogg_extension_required');
    $actual=filesize($f['tmp_name']); if($actual>$max) fail(413,'audio_too_large');
    if($actual!==$bytes || !hash_equals($sha,hash_file('sha256',$f['tmp_name']))) fail(400,'audio_integrity_mismatch');
    $mime=(new finfo(FILEINFO_MIME_TYPE))->file($f['tmp_name']);
    if(!in_array($mime,['audio/ogg','application/ogg','audio/opus','application/octet-stream'],true)) fail(400,'unsupported_audio_mime');
    $info=opus_info($f['tmp_name']);
    $tmp=private_root().'/tmp/'.bin2hex(random_bytes(16)).'.part';
    if(!move_uploaded_file($f['tmp_name'],$tmp)) fail(503,'storage_unavailable');
    $tx=false;
    try {
        $db->exec('BEGIN IMMEDIATE'); $tx=true;
        // Re-check revocation under the write lock, after potentially slow validation.
        $device=device_auth($db);
        $old=query($db,'SELECT * FROM calls WHERE tenant_id=? AND call_id=?',[$device['tenant_id'],$id])->fetch();
        if($old && ($old['device_id']!==$device['device_id'] || !hash_equals($old['audio_sha256'],$sha))) fail(409,'call_id_conflict');
        $rel=$device['tenant_id'].'/'.$device['device_id'].'/'.substr($id,0,2).'/'.$id.'.ogg';
        $dest=private_root().'/audio/'.$rel;
        if($old && $old['relative_path']!==$rel) fail(503,'stored_path_mismatch');
        mkdir_private(dirname($dest));
        if(is_file($dest)) {
            if(filesize($dest)!==$bytes || !hash_equals($sha,hash_file('sha256',$dest))) fail($old?503:409,$old?'stored_audio_corrupt':'orphan_audio_conflict');
        } else {
            $fh=fopen($tmp,'r+b'); if(!$fh) fail(503,'storage_unavailable');
            try { if(!fflush($fh) || (function_exists('fsync') && !fsync($fh))) fail(503,'storage_unavailable'); } finally { fclose($fh); }
            if(!rename($tmp,$dest)) fail(503,'storage_unavailable');
            // On process death before commit, deterministic dest is adopted on retry.
            // Never delete dest in the exception path: COMMIT may have succeeded.
        }
        $now=utc();
        if(!$old) {
            query($db,'INSERT INTO calls(call_id,tenant_id,device_id,operator_id,direction,remote_number,started_at,duration_ms,audio_duration_ms,app_build,audio_sha256,audio_bytes,codec,sample_rate,channels,channel_layout,left_role,right_role,original_filename,relative_path,received_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
                [$id,$device['tenant_id'],$device['device_id'],$device['operator_id'],$direction,$remote,$start,$duration,$info['audio_duration_ms'],$build,$sha,$bytes,'opus',48000,2,'stereo',$device['left_role'],$device['right_role'],$name,$rel,$now]);
            $serverId=(int)$db->lastInsertId();
            query($db,"INSERT INTO processing_jobs(server_call_id,stage,status,created_at) VALUES(?,'channel_split','waiting',?)",[$serverId,$now]);
        } else { $serverId=(int)$old['server_call_id']; $now=$old['received_at']; }
        query($db,'UPDATE devices SET last_seen_at=? WHERE device_id=?',[utc(),$device['device_id']]);
        $db->exec('COMMIT'); $tx=false;
        return ['ok'=>true,'call_id'=>$id,'server_call_id'=>$serverId,'stored'=>true,'duplicate'=>(bool)$old,'received_at'=>$now];
    } finally {
        if($tx) { try { $db->exec('ROLLBACK'); } catch(Throwable $ignored) {} }
        if(is_file($tmp)) @unlink($tmp);
    }
}

function dispatch(): never {
    security_headers(); $device=null;
    try {
        secure_transport();
        $path=parse_url($_SERVER['REQUEST_URI'] ?? '/',PHP_URL_PATH);
        $method=$_SERVER['REQUEST_METHOD'] ?? 'GET';
        if(in_array($path,['/health.php','/api/v1/health'],true) && $method==='GET') {
            $db=db(); $db->query('SELECT 1')->fetchColumn();
            if((int)$db->query('PRAGMA user_version')->fetchColumn()!==1) fail(503,'schema_mismatch');
            json_response(['ok'=>true,'service'=>'CallMonitor','version'=>CM_VERSION,'time'=>utc()]);
        }
        if($path==='/api/v1/status' && $method==='GET') { admin_auth(); json_response(status_data()); }
        if($path==='/api/v1/calls') {
            if($method!=='POST') { header('Allow: POST'); fail(405,'method_not_allowed'); }
            $db=db(); $device=device_auth($db); $result=receive_call($db,$device);
            $http=$result['duplicate']?200:201; audit($result['duplicate']?'duplicate':'stored',$http,$device,$result['call_id']);
            json_response($result,$http);
        }
        if(in_array($path,['/','/index.php','/status.php'],true) && in_array($method,['GET','POST'],true)) {
            header('Content-Type: text/html; charset=utf-8');
            $report=null;
            if($method==='POST') { admin_auth(field('token',160)); $report=status_data(); }
            echo '<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>CallMonitor — состояние сервера</title><style>body{font:16px system-ui;max-width:1000px;margin:40px auto;padding:0 20px;color:#172b3a}input{font:inherit;width:min(100%,500px);padding:10px;box-sizing:border-box}button{font:inherit;padding:10px 18px;margin:12px 0}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f1f5f9;padding:18px}h1{font-size:28px}</style><h1>CallMonitor</h1><p>Состояние приёма записей · версия '.CM_VERSION.'</p>';
            if($report) echo '<pre>'.htmlspecialchars(json_encode($report,JSON_PRETTY_PRINT|JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES),ENT_QUOTES,'UTF-8').'</pre><p><a href="/status.php">Закрыть отчёт</a></p>';
            else echo '<form method="post" action="/status.php"><label>Токен администратора<br><input type="password" name="token" required autocomplete="off" maxlength="160"></label><br><button>Показать состояние</button></form>';
            echo '</html>'; exit;
        }
        fail(404,'not_found');
    } catch(ApiError $e) { audit($e->reason,$e->status,$device); json_response(['ok'=>false,'error'=>$e->reason,'retryable'=>$e->status>=500],$e->status); }
      catch(Throwable $e) { audit('internal_error',503,$device); header('Retry-After: 30'); json_response(['ok'=>false,'error'=>'server_temporarily_unavailable','retryable'=>true],503); }
}
