@echo off
rem In a source checkout, demo-classes is always the newest successful build.
rem A running Java process can lock an older JAR on Windows, so do not let that
rem stale JAR override freshly compiled classes. Packaged releases use the root JAR.
set "BOMBIT_CP="
if exist "bomb-it-multiplayer.jar" set "BOMBIT_CP=bomb-it-multiplayer.jar"
if exist "target\bomb-it-multiplayer.jar" set "BOMBIT_CP=target\bomb-it-multiplayer.jar"
if exist "target\demo-classes" set "BOMBIT_CP=target\demo-classes"
if defined BOMBIT_CP exit /b 0
echo Build first with scripts\build-demo.cmd, or extract the complete release ZIP.
exit /b 1
