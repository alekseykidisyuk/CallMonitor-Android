<?php
declare(strict_types=1);
ini_set('display_errors','0');
$install=dirname(__DIR__).'/callmonitor_private/app/install.php';
if(!is_file($install)) { http_response_code(503); header('Content-Type: text/plain; charset=utf-8'); exit('Сначала распакуйте обе папки пакета в /home1/sensera.'); }
require $install;
install_page();
