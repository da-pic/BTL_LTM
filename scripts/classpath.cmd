@echo off
set "BOMBIT_CP=target\demo-classes"
if exist "target\bomb-it-multiplayer.jar" set "BOMBIT_CP=target\bomb-it-multiplayer.jar"
if exist "bomb-it-multiplayer.jar" set "BOMBIT_CP=bomb-it-multiplayer.jar"
if exist "%BOMBIT_CP%" exit /b 0
echo Build first with scripts\build-demo.cmd, or extract the complete release ZIP.
exit /b 1
