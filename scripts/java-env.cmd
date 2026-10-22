@echo off
rem Caller owns setlocal; do not change the user's persistent environment.
rem Accept the common JAVA_HOME=...\bin mistake within this script invocation.
if defined JAVA_HOME if not exist "%JAVA_HOME%\bin\java.exe" if exist "%JAVA_HOME%\java.exe" for %%D in ("%JAVA_HOME%\..") do set "JAVA_HOME=%%~fD"
if defined JAVA_HOME if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME="
set "BOMBIT_JAVA=java"
set "BOMBIT_JAVAC=javac"
if defined JAVA_HOME set "BOMBIT_JAVA=%JAVA_HOME%\bin\java.exe"
if defined JAVA_HOME set "BOMBIT_JAVAC=%JAVA_HOME%\bin\javac.exe"
