@echo off
setlocal enabledelayedexpansion

:: =====================================================
:: NIO Tunnel Startup Script for Windows
:: =====================================================
:: Usage: nio-tunnel.bat [start|stop|restart|status]
:: Double-click to start (default action)
:: =====================================================

:: Get script directory (where this .bat file is located)
set "SCRIPT_DIR=%~dp0"
:: Remove trailing backslash
set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
cd /d "%SCRIPT_DIR%"

:: Configuration - JAR is in the same directory as this script
set "APP_NAME=nio-tunnel"
set "JAR_FILE=%SCRIPT_DIR%\nio-tunnel.jar"
set "PID_FILE=%SCRIPT_DIR%\nio-tunnel.pid"
set "CONFIG_FILE=%SCRIPT_DIR%\config.properties"
set "JAVA_OPTS=-Xms64m -Xmx256m"

:: Check if JAR exists
if not exist "%JAR_FILE%" (
    echo [ERROR] JAR file not found: %JAR_FILE%
    echo Please build the project first: mvn clean package
    pause
    exit /b 1
)

:: Default action is 'start' (for double-click GUI usage)
set "ACTION=%~1"
if "%ACTION%"=="" set "ACTION=start"

:: Route to appropriate action
if /i "%ACTION%"=="start" goto :do_start
if /i "%ACTION%"=="stop" goto :do_stop
if /i "%ACTION%"=="restart" goto :do_restart
if /i "%ACTION%"=="status" goto :do_status
if /i "%ACTION%"=="help" goto :show_help
if /i "%ACTION%"=="-h" goto :show_help
if /i "%ACTION%"=="--help" goto :show_help

echo [ERROR] Unknown action: %ACTION%
goto :show_help

:: -----------------------------------------------------
:: START
:: -----------------------------------------------------
:do_start
:: Check if already running
call :check_running
if !RUNNING!==1 (
    echo [WARN] %APP_NAME% is already running (PID: !CURRENT_PID!)
    echo Use 'nio-tunnel.bat restart' to restart
    if "%~1"=="" pause
    exit /b 1
)

:: Start the application in background (no window)
start "" /b javaw %JAVA_OPTS% -jar "%JAR_FILE%"

:: Wait a moment and get PID
timeout /t 2 /nobreak >nul

:: Find the Java process running our JAR
for /f "tokens=2" %%i in ('wmic process where "commandline like '%%nio-tunnel%%' and name='javaw.exe'" get processid 2^>nul ^| findstr /r "[0-9]"') do (
    set "NEW_PID=%%i"
)

if defined NEW_PID (
    echo !NEW_PID!> "%PID_FILE%"
    :: If double-clicked (no args), silently exit - app runs in background
    if "%~1"=="" (
        exit /b 0
    )
    :: If run from command line, show status
    echo [OK] %APP_NAME% started successfully (PID: !NEW_PID!)
    echo.
    echo Listening on:
    echo   HTTP Proxy:   http://127.0.0.1:31281
    echo   SOCKS5 Proxy: socks5://127.0.0.1:31282
    echo.
) else (
    echo [ERROR] Failed to start %APP_NAME%
    if "%~1"=="" pause
    exit /b 1
)
goto :eof

:: -----------------------------------------------------
:: STOP
:: -----------------------------------------------------
:do_stop
echo Stopping %APP_NAME%...

call :check_running
if !RUNNING!==0 (
    echo [INFO] %APP_NAME% is not running
    if exist "%PID_FILE%" del "%PID_FILE%"
    goto :eof
)

:: Kill the process
taskkill /PID !CURRENT_PID! /F >nul 2>&1
if !errorlevel!==0 (
    echo [OK] %APP_NAME% stopped (PID: !CURRENT_PID!)
    if exist "%PID_FILE%" del "%PID_FILE%"
) else (
    echo [ERROR] Failed to stop %APP_NAME%
)
goto :eof

:: -----------------------------------------------------
:: RESTART
:: -----------------------------------------------------
:do_restart
echo Restarting %APP_NAME%...
call :do_stop
timeout /t 2 /nobreak >nul
:: For restart from command line, show output
set "SHOW_OUTPUT=1"
call :do_start_verbose
goto :eof

:do_start_verbose
call :check_running
if !RUNNING!==1 (
    echo [WARN] %APP_NAME% is already running (PID: !CURRENT_PID!)
    goto :eof
)

start "" /b javaw %JAVA_OPTS% -jar "%JAR_FILE%"
timeout /t 2 /nobreak >nul

for /f "tokens=2" %%i in ('wmic process where "commandline like '%%nio-tunnel%%' and name='javaw.exe'" get processid 2^>nul ^| findstr /r "[0-9]"') do (
    set "NEW_PID=%%i"
)

if defined NEW_PID (
    echo !NEW_PID!> "%PID_FILE%"
    echo [OK] %APP_NAME% started successfully (PID: !NEW_PID!)
    echo.
    echo Listening on:
    echo   HTTP Proxy:   http://127.0.0.1:31281
    echo   SOCKS5 Proxy: socks5://127.0.0.1:31282
) else (
    echo [ERROR] Failed to start %APP_NAME%
)
goto :eof

:: -----------------------------------------------------
:: STATUS
:: -----------------------------------------------------
:do_status
call :check_running
if !RUNNING!==1 (
    echo [RUNNING] %APP_NAME% is running (PID: !CURRENT_PID!)
) else (
    echo [STOPPED] %APP_NAME% is not running
)
goto :eof

:: -----------------------------------------------------
:: HELP
:: -----------------------------------------------------
:show_help
echo.
echo Usage: nio-tunnel.bat [command]
echo.
echo Commands:
echo   start     Start the proxy tunnel (default)
echo   stop      Stop the proxy tunnel
echo   restart   Restart the proxy tunnel
echo   status    Check if the proxy is running
echo   help      Show this help message
echo.
echo Double-click the .bat file to start silently in background.
echo.
if "%~1"=="" pause
exit /b 0

:: -----------------------------------------------------
:: Check if process is running
:: Sets RUNNING=1 and CURRENT_PID if running
:: -----------------------------------------------------
:check_running
set "RUNNING=0"
set "CURRENT_PID="

:: First try PID file
if exist "%PID_FILE%" (
    set /p CURRENT_PID=<"%PID_FILE%"
    :: Verify process is still running
    tasklist /FI "PID eq !CURRENT_PID!" 2>nul | findstr /i "javaw.exe java.exe" >nul
    if !errorlevel!==0 (
        set "RUNNING=1"
        goto :eof
    )
)

:: Fallback: search for running process
for /f "tokens=2" %%i in ('wmic process where "commandline like '%%nio-tunnel%%' and name='javaw.exe'" get processid 2^>nul ^| findstr /r "[0-9]"') do (
    set "CURRENT_PID=%%i"
    set "RUNNING=1"
    echo !CURRENT_PID!> "%PID_FILE%"
)
goto :eof

endlocal