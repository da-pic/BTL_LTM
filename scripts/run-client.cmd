@echo off
setlocal
pushd "%~dp0.."
call "%~dp0java-env.cmd"
call "%~dp0classpath.cmd"
if errorlevel 1 goto missing
"%BOMBIT_JAVA%" -cp "%BOMBIT_CP%" com.bombit.client.app.ClientApplication %*
set "demoExit=%errorlevel%"
popd
exit /b %demoExit%
:missing
echo Run scripts\build-demo.cmd first.
popd
exit /b 1
