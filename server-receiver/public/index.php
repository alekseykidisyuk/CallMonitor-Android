<?php
declare(strict_types=1);
ini_set('display_errors','0');
$bootstrap=dirname(__DIR__).'/callmonitor_private/app/api.php';
if(!is_file($bootstrap)) { http_response_code(503); header('Content-Type: application/json'); echo '{"ok":false,"error":"not_installed"}'; exit; }
require $bootstrap;
dispatch();
