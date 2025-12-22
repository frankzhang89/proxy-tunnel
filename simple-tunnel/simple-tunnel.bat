@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM Get the directory where the script is located and set it as the current directory.
set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"

REM Set variables
set APP_NAME=simple-tunnel
REM Using relative paths
set "JAR_FILE=%SCRIPT_DIR%simple-tunnel.jar"
set JAVA_OPTS=-Xmx64m -Xms16m
set "LOG_FILE=%SCRIPT_DIR%app.log"
set "JDK_ENV=JDK25"

REM  Parse the specified JDK path.
if defined JDK_ENV (
    set "JAVA_HOME=!%JDK_ENV%!"
    if defined JAVA_HOME (
        set "JAVA_BIN=!JAVA_HOME!\bin\javaw"
    )
)

REM Check if Java is installed.
if not defined JAVA_BIN (
    where javaw >nul 2>nul
    if %errorlevel% neq 0 (
        echo The specified JDK (%JDK_ENV%) was not found, and Java is not present in the system's PATH environment variable.
        echo Current directory: %CD%
        echo JAR file path: %JAR_FILE%
        pause
        exit /b 1
    )
    set "JAVA_BIN=javaw"
)

REM Check if the JAR file exists.
if not exist "%JAR_FILE%" (
    echo JAR file not found.: %JAR_FILE%
    echo Current directory: %CD%
    echo Seeking: %JAR_FILE%
    pause
    exit /b 1
)

echo Current directory: %CD%
echo JAR file : %JAR_FILE%
echo Engaging %APP_NAME%...

echo cost: %date% %time% >> "%LOG_FILE%"
echo Current directory: %CD% >> "%LOG_FILE%"
echo JAR file path: %JAR_FILE% >> "%LOG_FILE%"

REM 后台启动Java应用
start "simple-tunnel" /B "%JAVA_BIN%" %JAVA_OPTS% -jar "%JAR_FILE%" >> "%LOG_FILE%" 2>&1

if %errorlevel% equ 0 (
    echo %APP_NAME% Started！
    echo Log file: %LOG_FILE%
    timeout /t 3 /nobreak >nul
) else (
    echo Startup failed! Please check the log file.
    echo Log file: %LOG_FILE%
    pause
)

exit