# Invoked by startup.bat; arguments after the optional action are passed to Java.
$ErrorActionPreference = 'Stop'
$AppArguments = @($args)
$Action = 'restart'
if ($AppArguments.Count -gt 0 -and !$AppArguments[0].StartsWith('--')) {
    $Action = $AppArguments[0].ToLowerInvariant()
    $AppArguments = @($AppArguments | Select-Object -Skip 1)
}
# Keep access records in their configured file; explicit CLI options can override this default.
$AppArguments = @('--access.log.console=false') + $AppArguments
$ScriptDirectory = $PSScriptRoot
Set-Location -LiteralPath $ScriptDirectory
$PidFile = Join-Path $ScriptDirectory 'nio-tunnel.pid'
$StateFile = "$PidFile.json"
$LogDirectory = Join-Path $ScriptDirectory 'logs'
$OutLog = Join-Path $LogDirectory 'nio-tunnel.log'
$ErrorLog = Join-Path $LogDirectory 'nio-tunnel-error.log'
$OperationLock = $null
$ExitCode = 0

function Wait-Console([int]$Seconds) {
    # Redirected input must never block a scheduled or automated invocation.
    if ($Seconds -gt 0) { Write-Host "Closing in $Seconds seconds. Press any key to exit now." }
    else { Write-Host 'Press any key to exit.' }
    try { $Interactive = ![Console]::IsInputRedirected; $null = [Console]::KeyAvailable }
    catch { $Interactive = $false }
    if (!$Interactive) {
        if ($Seconds -gt 0) { Start-Sleep -Seconds $Seconds }
        return
    }
    $Timer = [Diagnostics.Stopwatch]::StartNew()
    while ($Seconds -eq 0 -or $Timer.Elapsed.TotalSeconds -lt $Seconds) {
        if ([Console]::KeyAvailable) { $null = [Console]::ReadKey($true); return }
        Start-Sleep -Milliseconds 100
    }
}

function Clear-State {
    Remove-Item -LiteralPath $PidFile, $StateFile -Force -ErrorAction SilentlyContinue
}

function Get-OwnedProcess {
    if (!(Test-Path -LiteralPath $PidFile)) { return $null }
    $ProcessIdText = (Get-Content -LiteralPath $PidFile -Raw).Trim()
    if ($ProcessIdText -notmatch '^[1-9][0-9]*$') { throw 'Invalid PID file. Refusing to stop an unidentified process.' }
    $ProcessId = [int]$ProcessIdText
    $Process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (!$Process) { Write-Host 'Removing stale PID file.'; Clear-State; return $null }
    if (!(Test-Path -LiteralPath $StateFile)) { throw 'Process identity file is missing. Refusing to stop an unidentified process.' }
    $State = Get-Content -LiteralPath $StateFile -Raw | ConvertFrom-Json
    # Creation time guards against PID reuse; executable and arguments guard identity.
    if ([string]$Process.StartTime.ToUniversalTime().Ticks -ne [string]$State.StartTimeTicks) {
        Write-Host 'Removing stale PID file (PID was reused).'; Clear-State; return $null
    }
    $Details = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId"
    $JarPattern = '(?:^|\s)"?-jar"?\s+(?:"' + [regex]::Escape($State.JarPath) + '"|' + [regex]::Escape($State.JarPath) + '(?=\s|$))'
    if (!$Details.ExecutablePath -or $Details.ExecutablePath -ine $State.ExecutablePath -or
            $Details.CommandLine -notmatch $JarPattern) {
        throw "Cannot verify process identity for PID $ProcessId. Refusing to stop it."
    }
    return $Process
}

function Stop-Tunnel {
    $Process = Get-OwnedProcess
    if (!$Process) { Write-Host 'nio-tunnel is not running. Continuing.'; return }
    Write-Host "Stopping nio-tunnel (PID $($Process.Id))..."
    # Windows termination does not guarantee Java shutdown hooks are executed.
    Stop-Process -InputObject $Process
    if (!$Process.WaitForExit(15000)) { throw 'Stop timed out after 15 seconds. Restart cancelled.' }
    Clear-State
    Write-Host 'nio-tunnel stopped.'
}

function ConvertTo-NativeArgument([string]$Value) {
    # Start-Process joins arguments; quote each one using Windows argv rules.
    '"' + ([regex]::Replace($Value, '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
}

function Start-DetachedJava([string]$Java, [string]$CommandLine) {
    # Pass ONLY the log and NUL handles to Java. Start-Process with redirection
    # can leak a caller's capture handles and keep its terminal invocation waiting.
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;
public static class TunnelProcess {
    [StructLayout(LayoutKind.Sequential)] struct SecurityAttributes {
        public int size; public IntPtr descriptor; public int inherit;
    }
    [StructLayout(LayoutKind.Sequential)] struct StartupInfo {
        public int size;
        public IntPtr reserved, desktop, title;
        public uint x, y, xSize, ySize, xChars, yChars, fill, flags;
        public ushort show, reservedSize;
        public IntPtr reservedData, input, output, error;
    }
    [StructLayout(LayoutKind.Sequential)] struct StartupInfoEx {
        public StartupInfo info; public IntPtr attributes;
    }
    [StructLayout(LayoutKind.Sequential)] struct ProcessInfo {
        public IntPtr process, thread; public int processId, threadId;
    }
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern SafeFileHandle CreateFile(string path, uint access, uint share,
        ref SecurityAttributes security, uint disposition, uint flags, IntPtr template);
    [DllImport("kernel32.dll", SetLastError=true)]
    static extern bool InitializeProcThreadAttributeList(IntPtr list, int count, int flags, ref IntPtr size);
    [DllImport("kernel32.dll", SetLastError=true)]
    static extern bool UpdateProcThreadAttribute(IntPtr list, uint flags, IntPtr attribute,
        IntPtr value, IntPtr size, IntPtr previous, IntPtr resultSize);
    [DllImport("kernel32.dll")] static extern void DeleteProcThreadAttributeList(IntPtr list);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern bool CreateProcess(string application, StringBuilder commandLine,
        IntPtr processSecurity, IntPtr threadSecurity, bool inherit, uint flags,
        IntPtr environment, string directory, ref StartupInfoEx startup, out ProcessInfo process);
    static void Check(bool success) {
        if (!success) throw new Win32Exception(Marshal.GetLastWin32Error());
    }
    public static int Launch(string java, string arguments, string directory, string output, string error) {
        var security = new SecurityAttributes { size=Marshal.SizeOf(typeof(SecurityAttributes)), inherit=1 };
        using (var input = CreateFile("NUL", 0x80000000, 3, ref security, 3, 0, IntPtr.Zero))
        using (var stdout = CreateFile(output, 0x40000000, 7, ref security, 2, 0, IntPtr.Zero))
        using (var stderr = CreateFile(error, 0x40000000, 7, ref security, 2, 0, IntPtr.Zero)) {
            Check(!input.IsInvalid && !stdout.IsInvalid && !stderr.IsInvalid);
            IntPtr size=IntPtr.Zero, attributes=IntPtr.Zero, handles=IntPtr.Zero;
            bool initialized=false;
            try {
                InitializeProcThreadAttributeList(IntPtr.Zero, 1, 0, ref size);
                attributes=Marshal.AllocHGlobal(size);
                Check(InitializeProcThreadAttributeList(attributes, 1, 0, ref size));
                initialized=true;
                handles=Marshal.AllocHGlobal(IntPtr.Size * 3);
                Marshal.WriteIntPtr(handles, 0, input.DangerousGetHandle());
                Marshal.WriteIntPtr(handles, IntPtr.Size, stdout.DangerousGetHandle());
                Marshal.WriteIntPtr(handles, IntPtr.Size * 2, stderr.DangerousGetHandle());
                Check(UpdateProcThreadAttribute(attributes, 0, new IntPtr(0x20002), handles,
                    new IntPtr(IntPtr.Size * 3), IntPtr.Zero, IntPtr.Zero));
                var startup = new StartupInfoEx();
                startup.info.size=Marshal.SizeOf(typeof(StartupInfoEx));
                startup.info.flags=0x100; // STARTF_USESTDHANDLES
                startup.info.input=input.DangerousGetHandle();
                startup.info.output=stdout.DangerousGetHandle();
                startup.info.error=stderr.DangerousGetHandle();
                startup.attributes=attributes;
                ProcessInfo process;
                // CREATE_NO_WINDOW | EXTENDED_STARTUPINFO_PRESENT | CREATE_NEW_PROCESS_GROUP
                Check(CreateProcess(java, new StringBuilder(arguments), IntPtr.Zero, IntPtr.Zero,
                    true, 0x08080200, IntPtr.Zero, directory, ref startup, out process));
                CloseHandle(process.thread); CloseHandle(process.process);
                return process.processId;
            } finally {
                if (initialized) DeleteProcThreadAttributeList(attributes);
                if (attributes != IntPtr.Zero) Marshal.FreeHGlobal(attributes);
                if (handles != IntPtr.Zero) Marshal.FreeHGlobal(handles);
            }
        }
    }
}
'@
    $FullCommandLine = (ConvertTo-NativeArgument $Java) + ' ' + $CommandLine
    $ProcessId = [TunnelProcess]::Launch($Java, $FullCommandLine, $ScriptDirectory, $OutLog, $ErrorLog)
    return [Diagnostics.Process]::GetProcessById($ProcessId)
}

function Get-Listeners {
    # Query all listeners so an empty result can be distinguished from a query failure.
    @(Get-NetTCPConnection -State Listen -ErrorAction Stop)
}

function Test-ConflictingAddress([string]$Address, [string]$BindAddress) {
    $Existing = [Net.IPAddress]::Parse($Address)
    $Requested = [Net.IPAddress]::Parse($BindAddress)
    if ($Existing.IsIPv4MappedToIPv6) { $Existing = $Existing.MapToIPv4(); $Address = $Existing.ToString() }
    if ($Requested.IsIPv4MappedToIPv6) { $Requested = $Requested.MapToIPv4(); $BindAddress = $Requested.ToString() }
    if ($Existing.Equals($Requested)) { return $true }
    # IPv6 wildcard listeners may also accept IPv4, depending on socket options.
    if ($Address -eq '::' -or $BindAddress -eq '::') { return $true }
    return $Existing.AddressFamily -eq $Requested.AddressFamily -and
        ($Address -eq '0.0.0.0' -or $BindAddress -eq '0.0.0.0')
}

function Start-Tunnel {
    $Existing = Get-OwnedProcess
    if ($Existing) { Write-Host "nio-tunnel is already running (PID $($Existing.Id)). No new process started."; return }
    $JarPath = if ($env:NIO_TUNNEL_JAR) { $env:NIO_TUNNEL_JAR } else { Join-Path $ScriptDirectory 'nio-tunnel.jar' }
    if (!(Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw "JAR not found: $JarPath. Set NIO_TUNNEL_JAR or copy the runnable JAR beside startup.bat." }
    $JarPath = (Resolve-Path -LiteralPath $JarPath).Path
    $Java = if ($env:NIO_TUNNEL_JAVA) { $env:NIO_TUNNEL_JAVA }
        elseif ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' }
        else { (Get-Command java.exe -ErrorAction Stop).Source }
    if (!(Test-Path -LiteralPath $Java -PathType Leaf)) { throw "Java executable not found: $Java" }

    # Source mode needs a JDK. The helper calls the JAR's own configuration loader.
    # Build argv explicitly: Windows PowerShell's native argument passing can split
    # --key=value options containing spaces, even when the BAT received them intact.
    $HelperArguments = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
        (Join-Path $ScriptDirectory 'StartupConfig.java'), $JarPath) + $AppArguments
    $HelperInfo = New-Object Diagnostics.ProcessStartInfo
    $HelperInfo.FileName = $Java
    $HelperInfo.Arguments = ($HelperArguments | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' '
    $HelperInfo.WorkingDirectory = $ScriptDirectory
    $HelperInfo.UseShellExecute = $false
    $HelperInfo.CreateNoWindow = $true
    $HelperInfo.RedirectStandardOutput = $true
    $HelperInfo.RedirectStandardError = $true
    $HelperInfo.StandardOutputEncoding = [Text.Encoding]::UTF8
    $HelperInfo.StandardErrorEncoding = [Text.Encoding]::UTF8
    $Helper = [Diagnostics.Process]::Start($HelperInfo)
    $OutputTask = $Helper.StandardOutput.ReadToEndAsync()
    $ErrorTask = $Helper.StandardError.ReadToEndAsync()
    $Helper.WaitForExit()
    $ConfigLines = @($OutputTask.Result -split '\r?\n')
    if ($ErrorTask.Result) { Write-Host $ErrorTask.Result.TrimEnd() }
    $HelperExit = $Helper.ExitCode
    $Helper.Dispose()
    if ($HelperExit -ne 0) { throw 'Unable to resolve configuration. A JDK 21 or newer and a runnable fat JAR are required.' }
    $Config = @{}
    foreach ($Line in $ConfigLines) {
        $Parts = ([string]$Line).Split('=', 2)
        if ($Parts.Length -eq 2) { $Config[$Parts[0]] = $Parts[1] }
    }
    $Ports = @([int]$Config.HTTP_PORT, [int]$Config.SOCKS_PORT)
    $Conflicts = @(Get-Listeners | Where-Object {
        $_.LocalPort -in $Ports -and (Test-ConflictingAddress $_.LocalAddress $Config.BIND_ADDRESS)
    })
    if ($Conflicts.Count -gt 0) {
        foreach ($Listener in $Conflicts) {
            $Owner = Get-CimInstance Win32_Process -Filter "ProcessId = $($Listener.OwningProcess)" -ErrorAction SilentlyContinue
            $Name = if ($Owner.Name) { $Owner.Name } else { 'Unavailable (permission denied)' }
            $Path = if ($Owner.ExecutablePath) { $Owner.ExecutablePath } else { 'Unavailable (permission denied)' }
            Write-Host "Port conflict: $($Listener.LocalAddress):$($Listener.LocalPort)"
            Write-Host "PID: $($Listener.OwningProcess) | Process: $Name | Path: $Path"
        }
        throw 'Required listener ports are occupied. No process was stopped.'
    }
    $null = New-Item -ItemType Directory -Path $LogDirectory -Force
    # Send SLF4J runtime messages to stdout, which is captured in nio-tunnel.log.
    $NativeArguments = @('-Dorg.slf4j.simpleLogger.logFile=System.out', '-jar', $JarPath) + $AppArguments
    $CommandLine = ($NativeArguments | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' '
    Write-Host 'Starting nio-tunnel...'
    $Process = Start-DetachedJava $Java $CommandLine
    try {
        # Record identity before checking readiness so failed launches remain manageable.
        $Details = Get-CimInstance Win32_Process -Filter "ProcessId = $($Process.Id)"
        if (!$Details -or !$Details.ExecutablePath) { throw 'Java exited before its identity could be recorded.' }
        $State = @{ StartTimeTicks = [string]$Process.StartTime.ToUniversalTime().Ticks
            ExecutablePath = $Details.ExecutablePath; JarPath = $JarPath }
        [IO.File]::WriteAllText($StateFile, ($State | ConvertTo-Json))
        [IO.File]::WriteAllText($PidFile, [string]$Process.Id)
        $Ready = $false
        $Timer = [Diagnostics.Stopwatch]::StartNew()
        while ($Timer.Elapsed.TotalSeconds -lt 20) {
            $Process.Refresh()
            if ($Process.HasExited) { throw "Java exited with code $($Process.ExitCode)." }
            $Bound = @(Get-Listeners | Where-Object { $_.OwningProcess -eq $Process.Id -and $_.LocalPort -in $Ports })
            if (@($Bound.LocalPort | Sort-Object -Unique).Count -eq 2) { $Ready = $true; break }
            Start-Sleep -Milliseconds 250
        }
        if (!$Ready) { throw 'Startup timed out before both listener ports became ready.' }
    } catch {
        $Failure = $_
        $Process.Refresh()
        if (!$Process.HasExited) { Stop-Process -InputObject $Process; $null = $Process.WaitForExit(15000) }
        if ($Process.HasExited) { Clear-State }
        throw "Startup failed: $Failure Check logs: $OutLog and $ErrorLog"
    }
    Write-Host "nio-tunnel started. PID: $($Process.Id)"
    Write-Host "HTTP: $($Config.LISTEN_HOST):$($Config.HTTP_PORT)"
    Write-Host "SOCKS: $($Config.LISTEN_HOST):$($Config.SOCKS_PORT)"
    Write-Host "Upstream: $($Config.UPSTREAM_HOST):$($Config.UPSTREAM_PORT)"
    Write-Host "Runtime log: $OutLog"
    Write-Host "Error log: $ErrorLog"
    Write-Host "Access log: $($Config.ACCESS_LOG)"
}

try {
    if ($Action -notin @('start', 'stop', 'restart') -or $AppArguments -contains '--help') {
        throw 'Usage: startup.bat [start|stop|restart] [--config=PATH] [--key=value ...]'
    }
    # Hold one exclusive lock across stop/start to prevent concurrent restarts.
    try { $OperationLock = [IO.File]::Open((Join-Path $ScriptDirectory '.startup.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch { throw 'Another lifecycle operation is running, or the script directory is not writable.' }
    if ($Action -in @('stop', 'restart')) { Stop-Tunnel }
    if ($Action -in @('start', 'restart')) { Start-Tunnel }
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    $ExitCode = 1
} finally {
    # Release the lock before the console delay or a key prompt.
    if ($OperationLock) { $OperationLock.Dispose() }
}
if ($ExitCode -eq 0) { Wait-Console 5 } else { Wait-Console 0 }
exit $ExitCode
