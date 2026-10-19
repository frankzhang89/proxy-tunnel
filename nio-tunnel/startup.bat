@echo off
setlocal DisableDelayedExpansion

rem Optional overrides. Otherwise JAVA_HOME, then PATH, supplies Java.
rem set "NIO_TUNNEL_JAVA=C:\Program Files\Java\jdk-21\bin\java.exe"
rem set "NIO_TUNNEL_JAR=D:\opt\packaging\nio-tunnel\nio-tunnel.jar"

rem PowerShell manages configuration, process identity, ports, and console input.
rem Without arguments, show the action menu with a five-second restart default.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0startup.ps1" %*
exit /b %errorlevel%
