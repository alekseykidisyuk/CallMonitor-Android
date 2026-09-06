param([string]$AudioPath, [string]$DeviceId = 'redmi-note12-01')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.Net.Http
$BaseUrl = 'https://callmonitor.sensera.online'
$report = [ordered]@{ version='0.1.0-D1'; started_at=[DateTimeOffset]::UtcNow.ToString('o'); ok=$false; checks=@(); responses=@() }
$reportPath = Join-Path $PSScriptRoot ('CallMonitor_DIAG_REPORT_' + (Get-Date -Format 'yyyyMMdd_HHmmss') + '.json')
$script:SensitiveValues = @()
$script:Phase = 'initialization'
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
function Safe-Text([string]$value) {
    if ($null -eq $value) { return '' }
    foreach ($secret in $script:SensitiveValues) {
        if ($secret) {
            $value=$value.Replace([string]$secret,'[REDACTED]')
            $value=$value.Replace([Uri]::EscapeDataString([string]$secret),'[REDACTED]')
            $value=$value.Replace([System.Net.WebUtility]::HtmlEncode([string]$secret),'[REDACTED]')
        }
    }
    $value=[regex]::Replace($value,'(?i)Bearer\s+[A-Za-z0-9_\-.*+/=]+','Bearer [REDACTED]')
    $value=[regex]::Replace($value,'(?<![0-9])\+?[0-9][0-9 ()\-]{6,20}[0-9](?![0-9])','[NUMBER_REDACTED]')
    return $value
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
        return Read-Response $response $path
    } finally { if ($response) { $response.Dispose() }; $request.Dispose() }
}
function Ogg-Diagnostics([byte[]]$bytes) {
    if (-not ('CMOggDiagD1' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Text;
public class CMOggDiagD1 {
 public int pages, packets, first_page_flags, last_page_flags, first_packet_bytes;
 public int channels, head_version, mapping_family, preskip;
 public uint input_rate;
 public long last_granule;
 public bool all_page_crc_ok=true, contiguous_sequence=true, single_stream=true;
 public bool opus_head, opus_tags, eos, unfinished_packet;
 public string framing_error;
 public static CMOggDiagD1 Inspect(byte[] b) {
  var d=new CMOggDiagD1(); int off=0, pending=0; uint serial=0, sequence=0;
  uint[] table=new uint[256];
  for(int i=0;i<256;i++){uint r=(uint)i<<24; for(int j=0;j<8;j++) r=(r<<1)^((r&0x80000000)!=0?0x04c11db7u:0u); table[i]=r;}
  while(off<b.Length){
   if(b.Length-off<27 || Encoding.ASCII.GetString(b,off,4)!="OggS"){d.framing_error="trailing_or_invalid_page";break;}
   int n=b[off+26], header=27+n, size=0;
   if(off+header>b.Length){d.framing_error="truncated_lacing";break;}
   for(int i=0;i<n;i++)size+=b[off+27+i];
   if(off+header+size>b.Length){d.framing_error="truncated_page";break;}
   uint s=BitConverter.ToUInt32(b,off+14), seq=BitConverter.ToUInt32(b,off+18);
   if(d.pages==0){serial=s;d.first_page_flags=b[off+5];}
   if(s!=serial)d.single_stream=false;
   if(seq!=sequence)d.contiguous_sequence=false;
   sequence=seq+1;
   uint crc=0;
   for(int i=0;i<header+size;i++){byte v=(i>=22&&i<26)?(byte)0:b[off+i];crc=(crc<<8)^table[((crc>>24)^v)&255];}
   if(crc!=BitConverter.ToUInt32(b,off+22))d.all_page_crc_ok=false;
   if(d.pages==0 && size>=19 && Encoding.ASCII.GetString(b,off+header,8)=="OpusHead"){
    int p=off+header;d.opus_head=true;d.head_version=b[p+8];d.channels=b[p+9];d.mapping_family=b[p+18];d.preskip=BitConverter.ToUInt16(b,p+10);d.input_rate=BitConverter.ToUInt32(b,p+12);
   }
   int boff=off+header;
   for(int i=0;i<n;i++){
    int len=b[off+27+i];
    if(d.packets==1 && pending==0 && len>=8 && Encoding.ASCII.GetString(b,boff,8)=="OpusTags")d.opus_tags=true;
    pending+=len;boff+=len;
    if(len<255){if(d.packets==0)d.first_packet_bytes=pending;d.packets++;pending=0;}
   }
   long granule=BitConverter.ToInt64(b,off+6);if(granule>=0)d.last_granule=granule;
   d.last_page_flags=b[off+5];d.pages++;off+=header+size;
  }
  d.eos=(d.last_page_flags&4)!=0;d.unfinished_packet=pending!=0;return d;
 }
}
'@
    }
    return [CMOggDiagD1]::Inspect($bytes)
}
function Read-Response($response, [string]$path) {
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $body = $null; $parseError=$null
        try { $body = $text | ConvertFrom-Json } catch { $parseError=Safe-Text $_.Exception.Message }
        $safeHeaders=[ordered]@{}
        foreach ($name in @('Server','Date','X-Request-ID','CF-Ray')) {
            if ($response.Headers.Contains($name)) { $safeHeaders[$name]=Safe-Text ([string]::Join(', ', $response.Headers.GetValues($name))) }
        }
        $contentType=Safe-Text ([string]$response.Content.Headers.ContentType)
        $encoding=Safe-Text ([string]::Join(', ', $response.Content.Headers.ContentEncoding))
        $snippet=Safe-Text $text
        if ($snippet.Length -gt 8192) { $snippet=$snippet.Substring(0,8192)+' [TRUNCATED]' }
        $script:report.responses += [ordered]@{
            step=$script:Phase; path=$path; http=[int]$response.StatusCode;
            content_type=$contentType; content_encoding=$encoding; response_characters=$text.Length;
            parsed_json=($null -ne $body); parse_error=$parseError;
            headers=$safeHeaders; response_preview=$snippet
        }
        return @{ code=[int]$response.StatusCode; body=$body }
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
    Write-Host 'CallMonitor 0.1.0-D1 - upload response and Ogg diagnostics'
    Write-Host 'The selected OGG will be uploaded to your CallMonitor server. Phone files are not changed.'
    if (-not $AudioPath) { $AudioPath=(Read-Host 'Full path to a verified build19 OGG file').Trim().Trim('"') }
    $file=Get-Item -LiteralPath $AudioPath
    $script:SensitiveValues += @($file.FullName,$file.Name)
    if ($file.Extension -ne '.ogg' -or $file.Length -gt 20MB) { throw 'Select an OGG recording smaller than 20 MiB for this smoke test' }
    [byte[]]$audio=[IO.File]::ReadAllBytes($file.FullName)
    $sha=Hash-Bytes $audio
    $duration=Audio-Info $audio
    $report.audio_diagnostics=Ogg-Diagnostics $audio
    $report.audio_sha256=$sha; $report.audio_bytes=$audio.Length; $report.duration_ms=$duration
    $statePath=Join-Path $PSScriptRoot ('CallMonitor_SMOKE_STATE_' + $sha.Substring(0,16) + '.json')
    $meta=$null
    if (Test-Path -LiteralPath $statePath) {
        $state=Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($state.audio_sha256 -ne $sha -or $state.device_id -ne $DeviceId) { throw 'Existing smoke state does not match this recording/device' }
        $meta=@{}; foreach ($p in $state.PSObject.Properties) { $meta[$p.Name]=[string]$p.Value }
    } else {
        throw 'Existing SMOKE_STATE file required. Extract this diagnostic next to the original RUN_SMOKE.cmd; do not remove state files.'

    }
    $script:SensitiveValues += @([string]$meta.remote_number,[string]$meta.original_filename)
    $report.call_id=$meta.call_id
    $token=Read-Token
    $script:SensitiveValues += $token
    $report.token_characters=$token.Length
    if ($token -notmatch '^[A-Za-z0-9_-]{43}$') { throw 'Device token must contain exactly 43 letters/digits/underscore/hyphen. Copy the device_token value without quotes.' }
    $script:Phase='health'
    $r=Send-Request '/health.php' '' $null $null
    Check ($r.code -eq 200 -and $r.body.ok) 'HTTPS health'
    $script:Phase='wrong_token'
    $r=Send-Request '/api/v1/calls' ('x'*43) $meta $audio
    Check ($r.code -eq 401) 'Wrong token rejected'
    $script:Phase='original_upload'
    $first=Send-Request '/api/v1/calls' $token $meta $audio
    if ($first.code -notin @(200,201)) {
        $reason='non-JSON response'; if ($null -ne $first.body) { $reason=[string]$first.body.error }
        throw ('Upload HTTP '+$first.code+': '+$reason)
    }
    Check ($first.body.ok -eq $true -and $first.body.stored -eq $true -and $first.body.call_id -eq $meta.call_id) 'Original recording accepted'
    $script:Phase='duplicate'
    $second=Send-Request '/api/v1/calls' $token $meta $audio
    Check ($second.code -eq 200 -and $second.body.duplicate -eq $true -and $second.body.call_id -eq $meta.call_id -and $second.body.server_call_id -eq $first.body.server_call_id) 'Retry returns same server call'
    [byte[]]$alt=[Convert]::FromBase64String((Get-Content -LiteralPath (Join-Path $PSScriptRoot 'alternate.ogg.b64') -Raw))
    $conflict=@{}; foreach($k in $meta.Keys) { $conflict[$k]=$meta[$k] }
    $conflict.audio_sha256=Hash-Bytes $alt; $conflict.audio_bytes=[string]$alt.Length; $conflict.duration_ms=[string](Audio-Info $alt)
    $script:Phase='conflict'
    $third=Send-Request '/api/v1/calls' $token $conflict $alt
    Check ($third.code -eq 409) 'Different valid audio cannot overwrite same call'
    $report.ok=$true; $report.call_id=$meta.call_id; $report.server_call_id=$first.body.server_call_id; $report.audio_sha256=$sha; $report.audio_bytes=$audio.Length; $report.duration_ms=$duration
} catch {
    $report.error=Safe-Text $_.Exception.Message
    Write-Host ('FAILED: '+$report.error) -ForegroundColor Red
} finally {
    $token=$null; $client.Dispose(); $handler.Dispose()
    $report.finished_at=[DateTimeOffset]::UtcNow.ToString('o')
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    Write-Host ('Report to send: '+$reportPath)
    Write-Host 'Do not send tokens or the SMOKE_STATE file.'
}
if (-not $report.ok) { exit 1 }
