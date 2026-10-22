@echo off
setlocal
pushd "%~dp0.."
call "%~dp0java-env.cmd"
if not defined BOMBIT_MVN set "BOMBIT_MVN=mvn.cmd"
call "%BOMBIT_MVN%" %* test
if errorlevel 1 goto failed
call scripts\check-tv2.cmd
if errorlevel 1 goto failed
"%BOMBIT_JAVA%" -cp "target/classes;target/test-classes" com.bombit.integration.NetworkTest
if errorlevel 1 goto failed
popd
exit /b 0
:failed
popd
exit /b 1
