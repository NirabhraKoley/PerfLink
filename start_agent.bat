@echo off
title PerfLink agent
cd /d "%~dp0agent"
python perflink_agent.py %*
pause
