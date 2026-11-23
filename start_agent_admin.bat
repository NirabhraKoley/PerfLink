@echo off
:: Starts the PerfLink agent as Administrator (asks once via the Windows UAC prompt).
:: Admin rights add CPU temperature and CPU package power to the stats.
net session >nul 2>&1
if %errorlevel% neq 0 (
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)
title PerfLink agent (Administrator)
cd /d "%~dp0agent"
python perflink_agent.py %*
pause
