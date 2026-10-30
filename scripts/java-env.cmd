@echo off
rem Goi demo uu tien runtime di kem, khong phu thuoc Java/Maven tren may thanh vien.
if exist "%~dp0..\runtime\bin\java.exe" (
    set "BOMBIT_JAVA=%~dp0..\runtime\bin\java.exe"
    set "BOMBIT_JAVAW=%~dp0..\runtime\bin\javaw.exe"
    set "BOMBIT_JAVAC=javac"
    exit /b 0
)
rem Caller owns setlocal; do not change the user's persistent environment.
rem Accept the common JAVA_HOME=...\bin mistake within this script invocation.
if defined JAVA_HOME if not exist "%JAVA_HOME%\bin\java.exe" if exist "%JAVA_HOME%\java.exe" for %%D in ("%JAVA_HOME%\..") do set "JAVA_HOME=%%~fD"
if defined JAVA_HOME if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME="
set "BOMBIT_JAVA=java"
set "BOMBIT_JAVAW=javaw"
set "BOMBIT_JAVAC=javac"
if defined JAVA_HOME set "BOMBIT_JAVA=%JAVA_HOME%\bin\java.exe"
if defined JAVA_HOME set "BOMBIT_JAVAW=%JAVA_HOME%\bin\javaw.exe"
if defined JAVA_HOME set "BOMBIT_JAVAC=%JAVA_HOME%\bin\javac.exe"
