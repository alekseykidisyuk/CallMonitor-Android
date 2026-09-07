@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0COLLECT_RECOVERY_D1.ps1"
pause
