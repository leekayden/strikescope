@echo off
setlocal
cd /d "%~dp0"

rem Builds the distributable single-file (fat) jar: app classes + FlatLaf merged.
rem Usage: build-release.bat <version>   e.g. build-release.bat 0.0.1
rem The version must match src\AppVersion.java (the GitHub release workflow
rem enforces the same rule for tagged builds).

set "VER=%~1"
if "%VER%"=="" (
  echo Usage: build-release.bat ^<version^>     e.g. build-release.bat 0.0.1
  exit /b 1
)
findstr /c:"\"%VER%\"" src\AppVersion.java >nul 2>nul
if errorlevel 1 (
  echo src\AppVersion.java does not contain "%VER%" - bump it there first
  exit /b 1
)

echo Compiling...
if not exist out mkdir out
javac -encoding UTF-8 -cp "lib\flatlaf.jar" -d out src\*.java
if errorlevel 1 (echo BUILD FAILED & exit /b 1)

echo Merging FlatLaf into fat jar...
if exist staging rmdir /s /q staging
mkdir staging
pushd staging
jar xf ..\lib\flatlaf.jar
rem a fat jar must stay a plain classpath jar: no manifest/module descriptor of its own
del META-INF\MANIFEST.MF 2>nul
del module-info.class 2>nul
popd

if not exist dist mkdir dist
jar --create --file "dist\StrikeScope-%VER%.jar" --main-class StrikeScopeApp -C out . -C staging .
if errorlevel 1 (echo JAR FAILED & exit /b 1)

echo.
echo Headless self-test:
java -jar "dist\StrikeScope-%VER%.jar" --selftest
if errorlevel 1 (echo SELFTEST FAILED & exit /b 1)

echo.
echo OK: dist\StrikeScope-%VER%.jar  (double-click to run; self-updating)
