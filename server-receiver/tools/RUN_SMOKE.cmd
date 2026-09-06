@echo off
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0CallMonitor_SMOKE_v010.ps1"
pause
