$ErrorActionPreference = 'Stop'
$path = Join-Path $PSScriptRoot '..\tools\CallMonitor_SMOKE_v010.ps1'
$tokens = $null
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors) | Out-Null
if ($errors.Count -gt 0) { $errors | Format-List; exit 1 }
Write-Output 'PASS: PowerShell syntax parsed'

# Execute the real metadata functions under Windows PowerShell 5.1. This
# validates .NET overloads and UInt64 granule arithmetic, not just parsing.
$ast = [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
$functions = $ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true)
foreach ($f in $functions) { Invoke-Expression $f.Extent.Text }
[byte[]]$audio = [Convert]::FromBase64String((Get-Content -LiteralPath (Join-Path $PSScriptRoot 'stereo.ogg.b64') -Raw))
if ((Audio-Info $audio) -ne 1000) { throw 'Ogg metadata duration incorrect' }
if ((Hash-Bytes ([Text.Encoding]::UTF8.GetBytes('abc'))) -ne 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad') { throw 'SHA helper incorrect' }
Add-Type -AssemblyName System.Net.Http
$handler = New-Object System.Net.Http.HttpClientHandler
$client = New-Object System.Net.Http.HttpClient($handler)
$request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, 'https://example.invalid/')
$multi = New-Object System.Net.Http.MultipartFormDataContent
$part = New-Object System.Net.Http.StringContent('test', [Text.Encoding]::UTF8)
$multi.Add($part, 'call_id')
$filePart = [System.Net.Http.ByteArrayContent]::new($audio)
$filePart.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('audio/ogg')
$multi.Add($filePart, 'audio', 'call.ogg')
$request.Content = $multi
if ($multi.ReadAsByteArrayAsync().GetAwaiter().GetResult().Length -le $audio.Length) { throw 'Multipart encoding failed' }
$request.Dispose(); $client.Dispose(); $handler.Dispose()
Write-Output 'PASS: Windows PowerShell 5.1 audio metadata, SHA and multipart runtime checks'
