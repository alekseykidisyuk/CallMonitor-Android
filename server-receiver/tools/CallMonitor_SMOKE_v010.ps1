param([string]$AudioPath, [string]$DeviceId = 'redmi-note12-01')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.Net.Http
$BaseUrl = 'https://callmonitor.sensera.online'
$report = [ordered]@{ version='0.1.0'; started_at=[DateTimeOffset]::UtcNow.ToString('o'); ok=$false; checks=@() }
$reportPath = Join-Path $PSScriptRoot ('CallMonitor_SMOKE_REPORT_' + (Get-Date -Format 'yyyyMMdd_HHmmss') + '.json')
$handler = New-Object System.Net.Http.HttpClientHandler
$handler.AllowAutoRedirect = $false
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(240)
$token = $null

function Read-Token {
    $secure = Read-Host 'Device token (input hidden)' -AsSecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr); $secure.Dispose() }
}
function Check([bool]$condition, [string]$name) {
    if (-not $condition) { throw ('CHECK FAILED: ' + $name) }
    Write-Host ('PASS: ' + $name) -ForegroundColor Green
    $script:report.checks += $name
}
function Send-Request([string]$path, [string]$auth, $fields, [byte[]]$audio) {
    $method = [System.Net.Http.HttpMethod]::Get
    if ($null -ne $fields) { $method = [System.Net.Http.HttpMethod]::Post }
    $request = New-Object System.Net.Http.HttpRequestMessage($method, ($BaseUrl + $path))
    $response = $null
    try {
        if ($auth) { $request.Headers.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $auth) }
        if ($null -ne $fields) {
            $multi = New-Object System.Net.Http.MultipartFormDataContent
            foreach ($key in $fields.Keys) {
                $part = New-Object System.Net.Http.StringContent([string]$fields[$key], [Text.Encoding]::UTF8)
                $multi.Add($part, [string]$key)
            }
            $filePart = [System.Net.Http.ByteArrayContent]::new($audio)
            $filePart.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('audio/ogg')
            $multi.Add($filePart, 'audio', 'call.ogg')
            $request.Content = $multi
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $body = $null
        try { $body = $text | ConvertFrom-Json } catch { }
        return @{ code=[int]$response.StatusCode; body=$body }
    } finally { if ($response) { $response.Dispose() }; $request.Dispose() }
}
function Audio-Info([byte[]]$bytes) {
    $offset=0; [UInt64]$granule=0; $preskip=0; $channels=0
    while ($offset -lt $bytes.Length) {
        if ($offset+27 -gt $bytes.Length -or [Text.Encoding]::ASCII.GetString($bytes,$offset,4) -ne 'OggS') { throw 'Invalid Ogg framing' }
        $segments=[int]$bytes[$offset+26]; $header=27+$segments; $length=0
        if ($offset+$header -gt $bytes.Length) { throw 'Truncated Ogg header' }
        for ($i=0; $i -lt $segments; $i++) { $length += [int]$bytes[$offset+27+$i] }
        if ($offset+$header+$length -gt $bytes.Length) { throw 'Truncated Ogg page' }
        if ($offset -eq 0) {
            if ($length -lt 19 -or [Text.Encoding]::ASCII.GetString($bytes,$header,8) -ne 'OpusHead') { throw 'Opus header required' }
            $channels=[int]$bytes[$header+9]; $preskip=[BitConverter]::ToUInt16($bytes,$header+10)
        }
        $g=[BitConverter]::ToUInt64($bytes,$offset+6)
        if ($g -ne [UInt64]::MaxValue) { $granule=$g }
        $offset += $header+$length
    }
    if ($channels -ne 2 -or $granule -le $preskip) { throw 'Complete stereo Opus recording required' }
    return [int64][Math]::Round(([double]$granule-$preskip)/48.0)
}
function Hash-Bytes([byte[]]$bytes) {
    $h=[Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($h.ComputeHash($bytes))).Replace('-','').ToLowerInvariant() }
    finally { $h.Dispose() }
}
try {
    Write-Host 'CallMonitor Server 0.1.0 - real stereo recording upload test'
    Write-Host 'The selected OGG will be uploaded to your CallMonitor server. Phone files are not changed.'
    if (-not $AudioPath) { $AudioPath=(Read-Host 'Full path to a verified build19 OGG file').Trim().Trim('"') }
    $file=Get-Item -LiteralPath $AudioPath
    if ($file.Extension -ne '.ogg' -or $file.Length -gt 20MB) { throw 'Select an OGG recording smaller than 20 MiB for this smoke test' }
    [byte[]]$audio=[IO.File]::ReadAllBytes($file.FullName)
    $sha=Hash-Bytes $audio
    $duration=Audio-Info $audio
    $statePath=Join-Path $PSScriptRoot ('CallMonitor_SMOKE_STATE_' + $sha.Substring(0,16) + '.json')
    $meta=$null
    if (Test-Path -LiteralPath $statePath) {
        $state=Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($state.audio_sha256 -ne $sha -or $state.device_id -ne $DeviceId) { throw 'Existing smoke state does not match this recording/device' }
        $meta=@{}; foreach ($p in $state.PSObject.Properties) { $meta[$p.Name]=[string]$p.Value }
    } else {
        $direction=''; $started=''; $remote=''
        if ($file.Name -match '^(\d{8})_(\d{6})\.(\d{3})([+-]\d{2})(\d{2})_(in|out)_(.*)\.ogg$') {
            $direction=$Matches[6]; $remote=$Matches[7]
            $stamp=$Matches[1]+'_'+$Matches[2]+'.'+$Matches[3]+$Matches[4]+':'+$Matches[5]
            $started=[DateTimeOffset]::ParseExact($stamp,'yyyyMMdd_HHmmss.fffzzz',[Globalization.CultureInfo]::InvariantCulture).ToString('o')
        } else {
            $direction=Read-Host 'Call direction: in or out'
            $started=Read-Host 'Call start with timezone, example 2026-09-07T10:30:00+05:00'
            $remote=Read-Host 'Remote phone number (Enter if unknown)'
        }
        if ($direction -notin @('in','out')) { throw 'Direction must be in or out' }
        # API accepts up to 6 fractional digits; .NET round-trip produces 7.
        $started=([DateTimeOffset]::Parse($started,[Globalization.CultureInfo]::InvariantCulture)).ToString('yyyy-MM-ddTHH:mm:ss.fffzzz')
        if ($remote -notmatch '^[+0-9() .#*\-]*$') { $remote='' }
        $meta=@{call_id=[Guid]::NewGuid().ToString(); device_id=$DeviceId; direction=$direction; remote_number=$remote; started_at=$started; duration_ms=[string]$duration; app_build='19'; audio_sha256=$sha; audio_bytes=[string]$audio.Length; codec='opus'; sample_rate='48000'; channels='2'; channel_layout='stereo'; left_role='operator'; right_role='client'; original_filename=$file.Name}
        $meta | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
    }
    $token=Read-Token
    $r=Send-Request '/health.php' '' $null $null
    Check ($r.code -eq 200 -and $r.body.ok) 'HTTPS health'
    $r=Send-Request '/api/v1/calls' ('x'*43) $meta $audio
    Check ($r.code -eq 401) 'Wrong token rejected'
    $first=Send-Request '/api/v1/calls' $token $meta $audio
    if ($first.code -notin @(200,201)) {
        $reason='non-JSON response'; if ($null -ne $first.body) { $reason=[string]$first.body.error }
        throw ('Upload HTTP '+$first.code+': '+$reason)
    }
    Check ($first.body.ok -eq $true -and $first.body.stored -eq $true -and $first.body.call_id -eq $meta.call_id) 'Original recording accepted'
    $second=Send-Request '/api/v1/calls' $token $meta $audio
    Check ($second.code -eq 200 -and $second.body.duplicate -eq $true -and $second.body.call_id -eq $meta.call_id -and $second.body.server_call_id -eq $first.body.server_call_id) 'Retry returns same server call'
    [byte[]]$alt=[Convert]::FromBase64String((Get-Content -LiteralPath (Join-Path $PSScriptRoot 'alternate.ogg.b64') -Raw))
    $conflict=@{}; foreach($k in $meta.Keys) { $conflict[$k]=$meta[$k] }
    $conflict.audio_sha256=Hash-Bytes $alt; $conflict.audio_bytes=[string]$alt.Length; $conflict.duration_ms=[string](Audio-Info $alt)
    $third=Send-Request '/api/v1/calls' $token $conflict $alt
    Check ($third.code -eq 409) 'Different valid audio cannot overwrite same call'
    $report.ok=$true; $report.call_id=$meta.call_id; $report.server_call_id=$first.body.server_call_id; $report.audio_sha256=$sha; $report.audio_bytes=$audio.Length; $report.duration_ms=$duration
} catch {
    $report.error=$_.Exception.Message
    Write-Host ('FAILED: '+$_.Exception.Message) -ForegroundColor Red
} finally {
    $token=$null; $client.Dispose(); $handler.Dispose()
    $report.finished_at=[DateTimeOffset]::UtcNow.ToString('o')
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    Write-Host ('Report to send: '+$reportPath)
    Write-Host 'Do not send tokens or the SMOKE_STATE file.'
}
if (-not $report.ok) { exit 1 }
