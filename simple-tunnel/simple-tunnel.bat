@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM 设置变量
set APP_NAME=simple-tunnel
set JAR_FILE=simple-tunnel.jar
set JAVA_OPTS=-Xmx64m -Xms16m
set LOG_FILE=app.log
REM 指定要使用的JDK环境变量名称（例如 JDK21 或 JDK17）
set "JDK_ENV=JDK21"

REM 解析指定的JDK路径
if defined JDK_ENV (
    set "JAVA_HOME=!%JDK_ENV%!"
    if defined JAVA_HOME (
        set "JAVA_BIN=!JAVA_HOME!\bin\javaw"
    )
)

REM 检查Java是否安装
if not defined JAVA_BIN (
    where javaw >nul 2>nul
    if %errorlevel% neq 0 (
        echo 未找到指定JDK（%JDK_ENV%）且系统PATH中也无java
        pause
        exit /b 1
    )
    set "JAVA_BIN=javaw"
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
start "JavaApp" /B "%JAVA_BIN%" %JAVA_OPTS% -jar "%JAR_FILE%" >> "%LOG_FILE%" 2>&1

if %errorlevel% equ 0 (
    echo %APP_NAME% 已启动！
    echo 日志文件: %LOG_FILE%
    timeout /t 3 /nobreak >nul
) else (
    echo 启动失败！
    pause
)

exit