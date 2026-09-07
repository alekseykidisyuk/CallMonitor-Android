$ErrorActionPreference = 'Stop'
$TestRoot = Join-Path $env:RUNNER_TEMP ('CallMonitor installer & test ' + [char]0x0422 + [guid]::NewGuid().ToString('N'))
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
        if (args.Length == 1 && args[0] == "devices") {
            Console.Error.WriteLine("STDERR-NOT-A-DEVICE\tdevice");
            Console.WriteLine("List of devices attached\nTEST-PHONE\tdevice");
            return 0;
        }
        if (args.Length < 3 || args[0] != "-s" || args[1] != "TEST-PHONE") return 3;
        if (args.Length == 6 && args[2] == "shell" && args[3] == "pm" && args[4] == "path" && args[5] == "com.baba.callvault") {
            Console.WriteLine("package:/data/app/mock/base.apk"); return 0;
        }
        if (args.Length == 5 && args[2] == "install" && args[3] == "-r" && args[4] == Environment.GetEnvironmentVariable("CM_EXPECT_APK")) {
            if (Environment.GetEnvironmentVariable("CM_ADB_FAIL") == "1") { Console.WriteLine("Failure [MOCK_ERROR]"); return 1; }
            Console.WriteLine("Success"); return 0;
        }
        if (args.Length == 6 && args[2] == "shell" && args[3] == "dumpsys" && args[4] == "package" && args[5] == "com.baba.callvault") {
            Console.WriteLine("versionCode=220021 minSdk=30 targetSdk=36"); return 0;
        }
        Console.Error.WriteLine("mock ADB: unexpected argument count or value");
        return 3;
    }
}
'@
$env:CM_ADB_TRACE = Join-Path $TestRoot 'trace.txt'
$Installer = Join-Path $TestRoot 'INSTALL_UPDATE.ps1'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'INSTALL_UPDATE.ps1') -Destination $Installer
$Apk = Join-Path $TestRoot 'CallMonitor-Android-build21.apk'
$env:CM_EXPECT_APK = $Apk
[IO.File]::WriteAllBytes($Apk, [byte[]](1,2,3,4))
$Hash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
@{apk_sha256=$Hash} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $TestRoot 'VERIFICATION.json') -Encoding UTF8
$Result = @(& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $Installer -AdbPath $MockPath 2>&1)
if ($LASTEXITCODE -ne 0 -or !(($Result -join "`n") -match 'PASS: CallMonitor build 21 installed')) { throw ($Result | Out-String) }
Write-Host 'PASS: exact ADB argv, Unicode/space/ampersand paths and isolated stderr'
# This malformed command used to pass a mock that only looked for "install -r".
$SavedPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$Malformed = @(& $MockPath -s TEST-PHONE $MockPath install -r $Apk 2>&1)
$MalformedExit = $LASTEXITCODE
$ErrorActionPreference = $SavedPreference
if ($MalformedExit -ne 3) { throw 'Mock accepted executable path in the command position' }
Write-Host 'PASS: regression mock rejects duplicated executable and extra arguments'
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
