@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM 设置变量
set APP_NAME=simple-tunnel
set JAR_FILE=simple-tunnel.jar
set JAVA_OPTS=-Xmx64m -Xms16m
set LOG_FILE=app.log

REM 检查Java是否安装
where java >nul 2>nul
if %errorlevel% neq 0 (
    echo Java未安装或未配置环境变量
    pause
    exit /b 1
)

REM 检查JAR文件是否存在
if not exist "%JAR_FILE%" (
    echo 找不到JAR文件: %JAR_FILE%
    pause
    exit /b 1
)

echo 正在启动%APP_NAME%...
echo 启动时间: %date% %time% >> "%LOG_FILE%"

REM 后台启动Java应用
start "JavaApp" /B javaw %JAVA_OPTS% -jar "%JAR_FILE%" >> "%LOG_FILE%" 2>&1

if %errorlevel% equ 0 (
    echo %APP_NAME% 已启动！
    echo 日志文件: %LOG_FILE%
    timeout /t 3 /nobreak >nul
) else (
    echo 启动失败！
    pause
)

exit