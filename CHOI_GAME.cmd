@echo off
setlocal
pushd "%~dp0"
call "scripts\java-env.cmd"
call "scripts\classpath.cmd"
if errorlevel 1 goto failed
rem Kiem tra bang java console truoc, tranh javaw dong im lang khi sai Java/JAR.
"%BOMBIT_JAVA%" -cp "%BOMBIT_CP%" com.bombit.client.app.ClientApplication --check-runtime
if errorlevel 1 goto failed
if "%~1"=="--check-runtime" goto done
start "" "%BOMBIT_JAVAW%" -cp "%BOMBIT_CP%" com.bombit.client.app.ClientApplication %*
if errorlevel 1 goto failed
:done
popd
exit /b 0
:failed
echo.
echo Khong mo duoc game. Hay giai nen TOAN BO goi demo truoc khi chay.
echo Ban khong kem runtime can Java 25. Source checkout can build-demo.cmd.
pause
popd
exit /b 1
