$ErrorActionPreference = 'Stop'
$TestRoot = Join-Path $env:RUNNER_TEMP ('CallMonitor installer test ' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $TestRoot | Out-Null
$MockPath = Join-Path $TestRoot 'adb.exe'
Add-Type -OutputAssembly $MockPath -OutputType ConsoleApplication -TypeDefinition @'
using System;
using System.IO;
public class MockAdb {
    public static int Main(string[] args) {
        string a = String.Join(" ", args);
        File.AppendAllText(Environment.GetEnvironmentVariable("CM_ADB_TRACE"), a + "\n");
        Console.Error.WriteLine("mock ADB daemon/progress message");
        if (a == "devices") { Console.WriteLine("List of devices attached\nTEST-PHONE\tdevice"); return 0; }
        if (a.Contains("pm path")) { Console.WriteLine("package:/data/app/mock/base.apk"); return 0; }
        if (a.Contains("install -r")) {
            if (Environment.GetEnvironmentVariable("CM_ADB_FAIL") == "1") { Console.WriteLine("Failure [MOCK_ERROR]"); return 1; }
            Console.WriteLine("Success"); return 0;
        }
        if (a.Contains("dumpsys package")) { Console.WriteLine("versionCode=220021 minSdk=30 targetSdk=36"); return 0; }
        return 3;
    }
}
'@
$env:CM_ADB_TRACE = Join-Path $TestRoot 'trace.txt'
$Installer = Join-Path $TestRoot 'INSTALL_UPDATE.ps1'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'INSTALL_UPDATE.ps1') -Destination $Installer
$Apk = Join-Path $TestRoot 'CallMonitor-Android-build21.apk'
[IO.File]::WriteAllBytes($Apk, [byte[]](1,2,3,4))
$Hash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
@{apk_sha256=$Hash} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $TestRoot 'VERIFICATION.json') -Encoding UTF8
$Result = @(& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $Installer -AdbPath $MockPath 2>&1)
if ($LASTEXITCODE -ne 0 -or !(($Result -join "`n") -match 'PASS: CallMonitor build 21 installed')) { throw ($Result | Out-String) }
Write-Host 'PASS: update succeeds despite native stderr and paths with spaces'
$env:CM_ADB_FAIL='1'
$Result = @(& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $Installer -AdbPath $MockPath 2>&1)
if ($LASTEXITCODE -ne 1 -or !(($Result -join "`n") -match 'DO NOT uninstall')) { throw 'Nonzero ADB exit was not handled safely' }
Write-Host 'PASS: installation failure stops without uninstalling'
$Before = (Get-Content -LiteralPath $env:CM_ADB_TRACE -Raw)
[IO.File]::WriteAllBytes($Apk, [byte[]](9,9,9))
$Result = @(& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $Installer -AdbPath $MockPath 2>&1)
if ($LASTEXITCODE -ne 1 -or !(($Result -join "`n") -match 'SHA-256 mismatch')) { throw 'Corrupt APK was not blocked' }
if ((Get-Content -LiteralPath $env:CM_ADB_TRACE -Raw) -ne $Before) { throw 'ADB ran despite invalid APK hash' }
if ($Before -match 'uninstall|pm clear') { throw 'Destructive command issued' }
Write-Host 'PASS: corrupt APK blocked before contacting device; no uninstall or clear commands'
# The final negative test intentionally returns 1; all assertions above passed.
exit 0
