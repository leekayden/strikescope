@echo off
rem Debug launcher: same app, but console java with errors captured to error.log
cd /d "%~dp0"
java -cp "%~dp0out;%~dp0lib\flatlaf.jar" StrikeScopeApp 2>"%~dp0error.log"
echo Exited. If no window appeared, open error.log and read the stack trace.
pause
