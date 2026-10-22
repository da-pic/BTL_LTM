@echo off
rem TV2: Bien dich toan bo production va demo/check khong can JUnit.
setlocal EnableDelayedExpansion
pushd "%~dp0.."
call "%~dp0java-env.cmd"
if not exist target\tv2-check mkdir target\tv2-check
(for /r src\main\java %%f in (*.java) do (
set "sourceFile=%%f"
echo "!sourceFile:\=/!"
)) > target\tv2-sources.txt
(for /r src\test\java %%f in (*Demo.java *Check.java) do (
set "sourceFile=%%f"
echo "!sourceFile:\=/!"
)) >> target\tv2-sources.txt
echo src/test/java/com/bombit/client/debug/GameDebugWindow.java >> target\tv2-sources.txt
"%BOMBIT_JAVAC%" --release 25 -encoding UTF-8 -d target/tv2-check @target/tv2-sources.txt
if errorlevel 1 goto failed
"%BOMBIT_JAVA%" -cp target/tv2-check com.bombit.server.game.movement.ApiMigrationCheck
if errorlevel 1 goto failed
"%BOMBIT_JAVA%" -cp target/tv2-check com.bombit.server.game.movement.MovementFeatureCheck
if errorlevel 1 goto failed
"%BOMBIT_JAVA%" -cp target/tv2-check com.bombit.server.game.runtime.RuntimeContractCheck
if errorlevel 1 goto failed
"%BOMBIT_JAVA%" -cp target/tv2-check com.bombit.server.game.movement.DeltaCompressionCheck > target/delta-compression-results.csv
if errorlevel 1 goto failed
type target\delta-compression-results.csv
popd
exit /b 0
:failed
popd
exit /b 1
