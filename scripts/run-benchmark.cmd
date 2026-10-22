@echo off
setlocal
pushd "%~dp0.."
call "%~dp0java-env.cmd"
call "%~dp0classpath.cmd"
if errorlevel 1 goto missing
"%BOMBIT_JAVA%" -cp "%BOMBIT_CP%" com.bombit.tools.NetworkBenchmark %*
set "benchmarkExit=%errorlevel%"
popd
exit /b %benchmarkExit%
:missing
popd
exit /b 1
