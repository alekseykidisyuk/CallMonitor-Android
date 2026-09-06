$ErrorActionPreference='Stop'
Set-StrictMode -Version 2
$path=Join-Path $PSScriptRoot '..\tools\CallMonitor_SMOKE_v010D1.ps1'
$tokens=$null; $errors=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
if($errors.Count){$errors|Format-List;throw 'Diagnostic syntax failed'}
foreach($f in $ast.FindAll({param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst]},$true)){Invoke-Expression $f.Extent.Text}
$script:SensitiveValues=@(('z'*43),'+998991234567','test-private.ogg')
$script:Phase='test'; $script:report=[ordered]@{responses=@()}
[byte[]]$a=[Convert]::FromBase64String((Get-Content (Join-Path $PSScriptRoot 'stereo.ogg.b64') -Raw))
$d=Ogg-Diagnostics $a
if(-not $d.all_page_crc_ok -or -not $d.eos -or -not $d.opus_head -or -not $d.opus_tags -or $d.channels -ne 2){throw 'Valid audio diagnostic failed'}
$a[$a.Length-10]=$a[$a.Length-10] -bxor 1
if((Ogg-Diagnostics $a).all_page_crc_ok){throw 'Damaged page CRC not detected'}
$bad=Ogg-Diagnostics ([byte[]]@(1,2,3))
if($bad.framing_error -ne 'trailing_or_invalid_page'){throw 'Bad framing not detected'}
Add-Type -AssemblyName System.Net.Http
$r=[System.Net.Http.HttpResponseMessage]::new([System.Net.HttpStatusCode]::BadRequest)
$r.Content=[System.Net.Http.StringContent]::new(('<html>Rejected '+('z'*43)+' +998991234567 test-private.ogg</html>'))
$result=Read-Response $r '/api/v1/calls'
if($result.code -ne 400 -or $null -ne $result.body){throw 'HTML 400 handling failed'}
$saved=$script:report|ConvertTo-Json -Depth 10
foreach($s in $script:SensitiveValues){if($saved.Contains($s)){throw 'Sensitive data leaked in diagnostics'}}
if(-not $saved.Contains('REDACTED') -or -not $saved.Contains('Rejected')){throw 'Safe response details missing'}
$r.Dispose()
$r=[System.Net.Http.HttpResponseMessage]::new([System.Net.HttpStatusCode]::BadRequest)
$r.Content=[System.Net.Http.StringContent]::new('{"ok":false,"error":"incomplete_ogg","retryable":false}')
$result=Read-Response $r '/api/v1/calls'
if($result.body.error -ne 'incomplete_ogg'){throw 'JSON error decoding failed'}
$r.Dispose()
Write-Output 'PASS: D1 syntax, C# Ogg CRC/framing, JSON/HTML 400 decoding, and secret redaction'
