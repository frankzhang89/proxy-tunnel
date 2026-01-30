@REM filepath: e:\work\Repository\githubRepository\proxy-tunnel\nio-tunnel\startup-background.bat
@echo off
setlocal enabledelayedexpansion

REM Set the script directory
set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"

REM Find the JAR file
set "JAR_FILE="
for %%f in (*.jar) do (
    set "JAR_FILE=%%f"
)

if "%JAR_FILE%"=="" (
    echo ERROR: No JAR file found in %SCRIPT_DIR%
    exit /b 1
)

REM Create log filename with timestamp
for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value') do set "datetime=%%I"
set "LOG_DATE=%datetime:~0,8%_%datetime:~8,6%"
set "LOG_FILE=%SCRIPT_DIR%nio-tunnel_%LOG_DATE%.log"

REM Java options
set "JAVA_OPTS=-Xms256m -Xmx512m"

REM Run the JAR in background and redirect output to log file
start /b javaw %JAVA_OPTS% -jar "%JAR_FILE%" >> "%LOG_FILE%" 2>&1