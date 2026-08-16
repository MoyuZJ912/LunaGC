@echo off
REM Start LunaGC server from the LunaGC directory.
REM Server-side monitors are started automatically in a hidden window.
REM Server stdout/stderr is redirected to server-run.log so the monitor can tail it.
type nul > "%~dp0server-run.log"
type nul > "%~dp0server-run.log.err"
echo [1/2] Starting server monitors...
start "LunaGC Server Monitors" /min powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\tools\start_all_monitors.ps1" -WaitTimeoutSec 180
echo [2/2] Starting LunaGC server (keep this window open)...
set "JAVA_21=C:\jdk-21.0.2\bin\java.exe"
if exist "%JAVA_21%" (
    "%JAVA_21%" -Xms512m -Xmx2048m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+HeapDumpOnOutOfMemoryError -Xlog:gc*:file=gc.log:time,uptime,level,tags -jar LunaGC-6.6.0.jar >> server-run.log 2>> server-run.log.err
) else (
    java -Xms512m -Xmx2048m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+HeapDumpOnOutOfMemoryError -Xlog:gc*:file=gc.log:time,uptime,level,tags -jar LunaGC-6.6.0.jar >> server-run.log 2>> server-run.log.err
)
pause
