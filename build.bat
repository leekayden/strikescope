@echo off
cd /d "%~dp0"
javac -encoding UTF-8 -cp "lib\flatlaf.jar" -d out src\*.java
if errorlevel 1 (echo BUILD FAILED) else (echo Build OK - run.bat to start the app)
