# CallMonitor build 20: update only. Never uninstall or clear application data.
$ErrorActionPreference = 'Stop'
try {
    $Root = Split-Path -Parent $MyInvocation.MyCommand.Path
    $Apk = Join-Path $Root 'CallMonitor-Android-build20.apk'
    $Manifest = Get-Content -LiteralPath (Join-Path $Root 'VERIFICATION.json') -Raw | ConvertFrom-Json
    if (!(Test-Path -LiteralPath $Apk)) { throw 'APK not found. Extract the entire archive first.' }
    $Hash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($Hash -ne $Manifest.apk_sha256) { throw 'APK SHA-256 mismatch. Download and extract the archive again.' }
    Write-Host 'PASS: APK SHA-256'
    $Adb = $null
    $Known = 'F:\Upload\platform-tools-latest-windows\platform-tools\adb.exe'
    if (Test-Path -LiteralPath $Known) { $Adb = $Known }
    else {
        $Command = Get-Command adb.exe -ErrorAction SilentlyContinue
        if ($null -ne $Command) { $Adb = $Command.Source }
    }
    if (!$Adb) { $Adb = (Read-Host 'Full path to adb.exe').Trim().Trim('"') }
    if (!(Test-Path -LiteralPath $Adb)) { throw 'adb.exe not found.' }
    $Listing = @(& $Adb devices 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'ADB could not list devices.' }
    $Devices = @($Listing | ForEach-Object { if ("$_" -match '^(\S+)\s+device$') { $Matches[1] } })
    if ($Devices.Count -ne 1) { throw 'Connect exactly one phone with USB debugging authorized, then run this installer again.' }
    $Serial = $Devices[0]
    $Installed = @(& $Adb -s $Serial shell pm path com.baba.callvault 2>&1)
    if ($LASTEXITCODE -ne 0 -or !(($Installed -join "`n") -match 'package:')) {
        throw 'Existing CallMonitor was not found. This script is for an update over build 19 only.'
    }
    Write-Host 'Installing build 20 as an update. Keep the phone connected; do not make calls during installation.'
    $Result = @(& $Adb -s $Serial install -r $Apk 2>&1)
    $Exit = $LASTEXITCODE
    $Result | ForEach-Object { Write-Host "$_" }
    if ($Exit -ne 0 -or !(($Result -join "`n") -match '(?m)^Success\s*$')) {
        throw 'Update failed. DO NOT uninstall the app. Send the error text.'
    }
    $Package = @(& $Adb -s $Serial shell dumpsys package com.baba.callvault 2>&1)
    if ($LASTEXITCODE -ne 0 -or !(($Package -join "`n") -match 'versionCode=220020\b')) {
        throw 'Installation returned success, but version 220020 was not confirmed. Send the output.'
    }
    Write-Host 'PASS: CallMonitor build 20 installed. Open the app, then Settings > CallMonitor to enter your device token.'
} catch {
    Write-Host ('FAILED: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
