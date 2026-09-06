<?php
declare(strict_types=1);
const CM_VERSION = '0.1.0';
umask(0077);
ini_set('display_errors', '0');
error_reporting(E_ALL);

final class ApiError extends RuntimeException {
    public function __construct(public int $status, public string $reason) { parent::__construct($reason); }
}
function fail(int $status, string $reason): never { throw new ApiError($status, $reason); }
function utc(): string { return gmdate('Y-m-d\TH:i:s\Z'); }
function private_root(): string { return dirname(__DIR__); }
function security_headers(): void {
    header('Cache-Control: no-store, private');
    header('X-Content-Type-Options: nosniff');
    header('Referrer-Policy: no-referrer');
    header("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
}
function secure_transport(): void {
    // Development exception is available ONLY to PHP's local built-in server.
    if (PHP_SAPI === 'cli-server' && getenv('CM_TEST_HTTP') === '1' && in_array($_SERVER['REMOTE_ADDR'] ?? '', ['127.0.0.1','::1'], true)) return;
    if (strtolower((string)($_SERVER['HTTPS'] ?? '')) !== 'on' && ($_SERVER['HTTPS'] ?? '') !== '1') fail(426, 'https_required');
    header('Strict-Transport-Security: max-age=31536000');
}
function json_response(array $data, int $status = 200): never {
    http_response_code($status); header('Content-Type: application/json; charset=utf-8');
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR); exit;
}
function cfg(): array {
    static $config;
    if ($config !== null) return $config;
    $path = private_root().'/config/config.php';
    if (!is_file($path)) fail(503, 'not_installed');
    return $config = require $path;
}
function db(): PDO {
    static $db;
    if ($db) return $db;
    cfg();
    $path = private_root().'/data/callmonitor.sqlite3';
    if (!is_file($path)) fail(503, 'database_missing');
    $db = new PDO('sqlite:'.$path, null, null, [PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE=>PDO::FETCH_ASSOC]);
    $db->exec('PRAGMA foreign_keys=ON; PRAGMA busy_timeout=10000; PRAGMA synchronous=FULL;');
    return $db;
}
function query(PDO $db, string $sql, array $args = []): PDOStatement {
    $s = $db->prepare($sql); $s->execute($args); return $s;
}
function bearer(): string {
    $h = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
    if (!preg_match('/^Bearer ([A-Za-z0-9_-]{40,160})$/D', $h, $m)) fail(401, 'invalid_credentials');
    return $m[1];
}
function device_auth(PDO $db): array {
    $hash = hash('sha256', bearer());
    $d = query($db, 'SELECT d.* FROM devices d JOIN tenants t ON t.tenant_id=d.tenant_id WHERE d.token_hash=? AND d.enabled=1 AND t.enabled=1', [$hash])->fetch();
    if (!$d) fail(401, 'invalid_credentials');
    return $d;
}
function admin_auth(?string $formToken = null): void {
    $token = $formToken ?? bearer();
    if (!hash_equals(cfg()['admin_token_hash'], hash('sha256', $token))) fail(401, 'invalid_credentials');
}
function field(string $key, int $max = 200, bool $optional = false): ?string {
    if (!array_key_exists($key, $_POST)) { if ($optional) return null; fail(400, 'missing_'.$key); }
    $v = $_POST[$key];
    if (!is_string($v) || strlen($v) > $max || preg_match('/[\x00-\x1f\x7f]/', $v) || !preg_match('//u', $v)) fail(400, 'invalid_'.$key);
    return $v;
}
function integer_field(string $key, int $min, int $max): int {
    $v = field($key, 18);
    if (!preg_match('/^(0|[1-9][0-9]*)$/D', $v) || (float)$v < $min || (float)$v > $max) fail(400, 'invalid_'.$key);
    return (int)$v;
}
function mkdir_private(string $dir): void {
    if (!is_dir($dir) && !mkdir($dir, 0700, true) && !is_dir($dir)) throw new RuntimeException('mkdir_failed');
}
function atomic_text(string $path, string $data): void {
    $tmp = $path.'.'.bin2hex(random_bytes(8)).'.tmp';
    $h = fopen($tmp, 'xb'); if (!$h) throw new RuntimeException('write_failed');
    try {
        if (fwrite($h, $data) !== strlen($data) || !fflush($h)) throw new RuntimeException('write_failed');
        if (function_exists('fsync') && !fsync($h)) throw new RuntimeException('sync_failed');
    } finally { fclose($h); }
    if (!rename($tmp, $path)) { @unlink($tmp); throw new RuntimeException('rename_failed'); }
}
function token(): string { return rtrim(strtr(base64_encode(random_bytes(32)), '+/', '-_'), '='); }
function masked(?string $number): ?string {
    if (!$number) return null;
    return '***'.substr($number, -4);
}
function audit(string $result, int $http, ?array $device = null, ?string $callId = null): void {
    // Bounded monthly file, no credentials, remote numbers or raw exception strings.
    $path = private_root().'/logs/events-'.gmdate('Y-m').'.jsonl';
    if (!is_dir(dirname($path))) return;
    $h = @fopen($path, 'ab'); if (!$h) return;
    if (flock($h, LOCK_EX)) {
        $size = fstat($h)['size'];
        if ($size < 10*1024*1024) fwrite($h, json_encode(['at'=>utc(),'result'=>$result,'http'=>$http,'device_id'=>$device['device_id'] ?? null,'call_id'=>$callId])."\n");
        flock($h, LOCK_UN);
    }
    fclose($h);
}
function status_data(): array {
    $db = db();
    $calls = query($db, 'SELECT call_id,device_id,tenant_id,direction,remote_number,started_at,duration_ms,audio_duration_ms,audio_bytes,audio_sha256,channels,received_at FROM calls ORDER BY server_call_id DESC LIMIT 30')->fetchAll();
    foreach ($calls as &$c) $c['remote_number'] = masked($c['remote_number']);
    return ['ok'=>true,'version'=>CM_VERSION,'time'=>utc(),'calls_total'=>(int)$db->query('SELECT COUNT(*) FROM calls')->fetchColumn(),
        'audio_bytes_total'=>(int)$db->query('SELECT COALESCE(SUM(audio_bytes),0) FROM calls')->fetchColumn(),
        'devices'=>query($db,'SELECT device_id,tenant_id,operator_id,enabled,left_role,right_role,last_seen_at FROM devices ORDER BY tenant_id,device_id')->fetchAll(),
        'last_calls'=>$calls,'max_audio_bytes'=>cfg()['max_audio_bytes']];
}
