$ErrorActionPreference = 'Stop'
$path = Join-Path $PSScriptRoot '..\tools\CallMonitor_SMOKE_v010.ps1'
$tokens = $null
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors) | Out-Null
if ($errors.Count -gt 0) { $errors | Format-List; exit 1 }
Write-Output 'PASS: PowerShell syntax parsed'
