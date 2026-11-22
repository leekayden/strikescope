@echo off
setlocal
cd /d "%~dp0"

rem --- locate a JVM: PATH first, then common Adoptium install dirs ---
set "JAVAW=javaw"
where javaw >nul 2>nul
if not errorlevel 1 goto :found

for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-*") do (
  if exist "%%D\bin\javaw.exe" set "JAVAW=%%D\bin\javaw.exe"
)
if exist "%JAVAW%" goto :found

echo Could not find javaw.exe.
echo Install JDK 17 (Eclipse Temurin) or add its bin folder to PATH.
pause
exit /b 1

:found
start "StrikeScope" /D "%~dp0" "%JAVAW%" -cp "%~dp0out;%~dp0lib\flatlaf.jar" StrikeScopeApp
exit /b 0
