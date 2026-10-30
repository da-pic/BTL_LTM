@echo off
setlocal
title BombIt - Server Classic / Shadow Relay / Paradox
call "%~dp0scripts\run-server.cmd" %*
set "serverExit=%errorlevel%"
if "%serverExit%"=="0" exit /b 0
echo.
echo Khong khoi dong duoc may chu. Kiem tra loi ben tren va cong 8888.
pause
exit /b %serverExit%
