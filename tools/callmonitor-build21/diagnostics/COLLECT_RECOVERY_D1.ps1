# CallMonitor recovery diagnostics D1. Read only; no install, settings writes or log clearing.
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
        if (!$Process.WaitForExit(30000)) {
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
function Capture-Adb {
    param([string[]]$CommandArgs, [string]$KeepPattern)
    $Result = Invoke-AdbCheckedOutput -Arguments (@('-s', $script:Serial) + $CommandArgs)
    $Lines = @($Result.Lines)
    if ($KeepPattern) { $Lines = @($Lines | Where-Object { $_ -match $KeepPattern }) }
    # Defense in depth for diagnostic lines; upload credentials are never read from preferences.
    $Lines = @($Lines | ForEach-Object {
        $_ -replace '(?i)(Bearer\s+)[^\s]+', '$1[redacted]' -replace '(?i)((?:device_token|admin_token|token)\s*[=:]\s*)[^\s,;]+', '$1[redacted]'
    })
    return [ordered]@{ exit_code = $Result.ExitCode; lines = $Lines }
}
try {
    $Adb = $AdbPath
    if (!$Adb) { $Adb = 'F:\Upload\platform-tools-latest-windows\platform-tools\adb.exe' }
    if (!(Test-Path -LiteralPath $Adb)) { $Adb = (Read-Host 'Full path to adb.exe').Trim().Trim('"') }
    if (!(Test-Path -LiteralPath $Adb)) { throw 'adb.exe not found.' }
    Write-Host 'CallMonitor recovery D1: READ ONLY. Keep Wi-Fi as it is. Do not reboot.'
    $Call = Invoke-AdbCheckedOutput -Arguments @('devices')
    if ($Call.ExitCode -ne 0) { throw 'ADB could not list devices.' }
    $Devices = @($Call.Lines | ForEach-Object { if ($_ -match '^([A-Za-z0-9._:-]+)\s+device\s*$') { $Matches[1] } })
    if ($Devices.Count -ne 1) { throw 'Connect exactly one authorized phone by USB, then rerun.' }
    $script:Serial = $Devices[0]
    $Report = [ordered]@{ diagnostic = 'CallMonitor-Recovery-D1'; read_only = $true; started_at = [DateTime]::UtcNow.ToString('o'); captures = [ordered]@{} }
    $Tags = 'CM:HyperOsAccessibility|CV:AdbConnectionService|CV:DaemonKeepAlive|CV:AdbShell|CV:RecorderConnection|CV:RecorderBackend'
    # Read history FIRST, before other diagnostic commands add logcat traffic.
    $Report.captures.logcat = Capture-Adb -CommandArgs @('logcat','-d','-v','threadtime','-t','6000') -KeepPattern $Tags
    $Report.captures.app_log = Capture-Adb -CommandArgs @('shell','run-as','com.baba.callvault','tail','-n','3000','cache/app_debug.log') -KeepPattern $Tags
    $Report.captures.device_time = Capture-Adb -CommandArgs @('shell','date')
    $Report.captures.uptime = Capture-Adb -CommandArgs @('shell','cat','/proc/uptime')
    foreach ($Name in @('adb_wifi_enabled','adb_enabled','wifi_on','boot_count','development_settings_enabled')) {
        $Report.captures[$Name] = Capture-Adb -CommandArgs @('shell','settings','get','global',$Name)
    }
    $Report.captures.package = Capture-Adb -CommandArgs @('shell','dumpsys','package','com.baba.callvault') -KeepPattern 'versionCode=|versionName=|lastUpdateTime=|WRITE_SECURE_SETTINGS|ACCESS_NETWORK_STATE'
    $Report.captures.cpu = Capture-Adb -CommandArgs @('shell','dumpsys','cpuinfo') -KeepPattern 'com\.baba\.callvault|CPU usage|TOTAL'
    $Services = Capture-Adb -CommandArgs @('shell','settings','get','secure','enabled_accessibility_services')
    $Report.captures.accessibility = [ordered]@{ exit_code = $Services.exit_code; callmonitor_enabled = (($Services.lines -join '') -match 'com\.baba\.callvault/') }
    $Prefs = Capture-Adb -CommandArgs @('shell','run-as','com.baba.callvault','cat','shared_prefs/callmonitor_hyperos_accessibility.xml')
    $State = [ordered]@{ exit_code = $Prefs.exit_code; values = [ordered]@{} }
    if ($Prefs.exit_code -eq 0) {
        try {
            [xml]$Xml = $Prefs.lines -join "`n"
            foreach ($Node in $Xml.SelectNodes('/map/*')) {
                $Name = $Node.GetAttribute('name')
                if ($Name -in @('wireless_debug_recovery_pending','ui_boot','ui_attempts','ui_deadline','ui_next_start')) {
                    $State.values[$Name] = $Node.GetAttribute('value')
                }
            }
        } catch { $State['parse_error'] = 'Recovery preferences could not be parsed.' }
    }
    $Report.captures.recovery_state = $State
    $Report['finished_at'] = [DateTime]::UtcNow.ToString('o')
    $ReportPath = Join-Path $PSScriptRoot ('CallMonitor_RECOVERY_D1_' + (Get-Date -Format 'yyyyMMdd_HHmmss') + '.json')
    $Report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
    Write-Host ('REPORT TO SEND: ' + $ReportPath)
    Write-Host 'DONE: diagnostics saved. Nonzero optional capture codes are included in the report.'
    exit 0
} catch {
    Write-Host ('FAILED: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
