# CallMonitor build 21 installer I1: update only. Never uninstall or clear application data.
param([string]$AdbPath)
$ErrorActionPreference = 'Stop'
function Invoke-AdbCheckedOutput {
    param([string[]]$Arguments)
    # Keep the executable separate from argv. Do not use PowerShell native-command
    # splatting/redirection: Windows PowerShell 5.1 and ADB stderr differ by host.
    if (!$Arguments -or $Arguments.Count -eq 0) { throw 'Missing ADB command.' }
    $Quoted = foreach ($Argument in $Arguments) {
        if ([string]::IsNullOrEmpty($Argument) -or $Argument -match '["\r\n\x00]' -or $Argument.EndsWith('\')) {
            throw 'Invalid ADB argument. No command was executed.'
        }
        '"' + $Argument + '"'
    }
    $StartInfo = New-Object System.Diagnostics.ProcessStartInfo
    $StartInfo.FileName = $script:Adb
    $StartInfo.Arguments = $Quoted -join ' '
    $StartInfo.UseShellExecute = $false
    $StartInfo.CreateNoWindow = $true
    $StartInfo.RedirectStandardOutput = $true
    $StartInfo.RedirectStandardError = $true
    Write-Host ('ADB arguments: ' + $StartInfo.Arguments)
    $Process = New-Object System.Diagnostics.Process
    $Process.StartInfo = $StartInfo
    try {
        if (!$Process.Start()) { throw 'ADB process did not start.' }
        # Drain both pipes concurrently; progress on stderr must not block stdout.
        $StdoutTask = $Process.StandardOutput.ReadToEndAsync()
        $StderrTask = $Process.StandardError.ReadToEndAsync()
        if (!$Process.WaitForExit(300000)) {
            $Process.Kill()
            throw 'ADB timed out. Keep the app installed and send this output.'
        }
        $Stdout = $StdoutTask.GetAwaiter().GetResult()
        $Stderr = $StderrTask.GetAwaiter().GetResult()
        $Code = $Process.ExitCode
        # Device/package/Success parsing uses stdout only; stderr remains diagnostic.
        if ($Stderr) { Write-Host $Stderr.TrimEnd() }
        return [pscustomobject]@{ Lines = @($Stdout -split '\r?\n'); ExitCode = $Code }
    } finally { $Process.Dispose() }
}
try {
    $Root = Split-Path -Parent $MyInvocation.MyCommand.Path
    $Apk = Join-Path $Root 'CallMonitor-Android-build21.apk'
    $Manifest = Get-Content -LiteralPath (Join-Path $Root 'VERIFICATION.json') -Raw | ConvertFrom-Json
    if (!(Test-Path -LiteralPath $Apk)) { throw 'APK not found. Extract the entire archive first.' }
    $Hash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($Hash -ne $Manifest.apk_sha256) { throw 'APK SHA-256 mismatch. Download and extract the archive again.' }
    Write-Host 'PASS: APK SHA-256'
    $Adb = $AdbPath
    $Known = 'F:\Upload\platform-tools-latest-windows\platform-tools\adb.exe'
    if (!$Adb -and (Test-Path -LiteralPath $Known)) { $Adb = $Known }
    if (!$Adb) {
        $Command = Get-Command adb.exe -ErrorAction SilentlyContinue
        if ($null -ne $Command) { $Adb = $Command.Source }
    }
    if (!$Adb) { $Adb = (Read-Host 'Full path to adb.exe').Trim().Trim('"') }
    if (!(Test-Path -LiteralPath $Adb)) { throw 'adb.exe not found.' }
    $Call = Invoke-AdbCheckedOutput -Arguments @('devices')
    $Listing = $Call.Lines
    if ($Call.ExitCode -ne 0) { throw 'ADB could not list devices.' }
    $Devices = @($Listing | ForEach-Object { if ("$_" -match '^([A-Za-z0-9._:-]+)\s+device\s*$') { $Matches[1] } })
    if ($Devices.Count -ne 1) { throw 'Connect exactly one phone with USB debugging authorized, then run this installer again.' }
    $Serial = $Devices[0]
    $Call = Invoke-AdbCheckedOutput -Arguments @('-s', $Serial, 'shell', 'pm', 'path', 'com.baba.callvault')
    $Installed = $Call.Lines
    if ($Call.ExitCode -ne 0 -or !(($Installed -join "`n") -match 'package:')) {
        throw 'Existing CallMonitor was not found. This script is for an update over an existing CallMonitor installation only.'
    }
    Write-Host 'Installing build 21 as an update. Keep the phone connected; do not make calls during installation.'
    $Call = Invoke-AdbCheckedOutput -Arguments @('-s', $Serial, 'install', '-r', $Apk)
    $Result = $Call.Lines
    $Exit = $Call.ExitCode
    $Result | ForEach-Object { Write-Host "$_" }
    if ($Exit -ne 0 -or !(($Result -join "`n") -match '(?m)^Success\s*$')) {
        throw 'Update failed. DO NOT uninstall the app. Send the error text.'
    }
    $Call = Invoke-AdbCheckedOutput -Arguments @('-s', $Serial, 'shell', 'dumpsys', 'package', 'com.baba.callvault')
    $Package = $Call.Lines
    if ($Call.ExitCode -ne 0 -or !(($Package -join "`n") -match 'versionCode=220021\b')) {
        throw 'Installation returned success, but version 220021 was not confirmed. Send the output.'
    }
    Write-Host 'PASS: CallMonitor build 21 installed. Open the app. Existing token and upload queue are preserved.'
} catch {
    Write-Host ('FAILED: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
