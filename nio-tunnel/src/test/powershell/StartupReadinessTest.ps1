# Run with Windows PowerShell 5.1: powershell.exe -NoProfile -File .\StartupReadinessTest.ps1
$ErrorActionPreference = 'Stop'
$LauncherPath = Join-Path $PSScriptRoot '..\..\..\startup.ps1'
$ParseErrors = $null
$Tokens = $null
$Ast = [Management.Automation.Language.Parser]::ParseFile($LauncherPath, [ref]$Tokens, [ref]$ParseErrors)
if ($ParseErrors.Count -gt 0) { throw ($ParseErrors | Out-String) }

# Load only the readiness functions, without running the launcher's lifecycle code.
foreach ($Name in @('Get-ListenerCandidates', 'Test-ConflictingAddress', 'Test-TcpListener', 'Test-ListenersReady')) {
    $Definition = $Ast.Find({
        param($Node)
        $Node -is [Management.Automation.Language.FunctionDefinitionAst] -and $Node.Name -eq $Name
    }, $false)
    if (!$Definition) { throw "Missing launcher function: $Name" }
    . ([scriptblock]::Create($Definition.Extent.Text))
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (!$Condition) { throw $Message }
    Write-Host "PASS: $Message"
}

function New-Endpoint([int]$Port, [int]$Owner, [string]$Address = '127.0.0.1') {
    [pscustomobject]@{
        LocalAddress = $Address; LocalPort = $Port
        RemotePort = 0; OwningProcess = $Owner; State = 0
    }
}

# Simulate the provider that reports State=0. No State parameter is accepted,
# so reintroducing a Listen filter also fails this regression test.
function Get-NetTCPConnection {
    [CmdletBinding()]
    param()
    if ($script:QueryFails) { throw 'Simulated TCP query failure' }
    $script:TcpRows
}

$Http = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$Socks = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$Bound = [Net.Sockets.Socket]::new([Net.Sockets.AddressFamily]::InterNetwork,
    [Net.Sockets.SocketType]::Stream, [Net.Sockets.ProtocolType]::Tcp)
try {
    $Http.Start()
    $Socks.Start()
    $Bound.ExclusiveAddressUse = $true
    $Bound.Bind([Net.IPEndPoint]::new([Net.IPAddress]::Loopback, 0))
    $Ports = @($Http.LocalEndpoint.Port, $Socks.LocalEndpoint.Port)
    $Endpoints = @((New-Endpoint $Ports[0] $PID), (New-Endpoint $Ports[1] $PID))
    $Established = [pscustomobject]@{
        LocalAddress = '127.0.0.1'; LocalPort = $Ports[0]
        RemotePort = 12345; OwningProcess = $PID; State = 5
    }
    $script:TcpRows = $Endpoints + @($Established)
    $Candidates = @(Get-ListenerCandidates)
    Assert-True ($Candidates.Count -eq 2) 'State=0 listeners are retained; connected sockets are excluded'
    Assert-True (Test-ListenersReady $Candidates $PID $Ports '127.0.0.1') 'Both owned, accepting listeners are ready even with State=0'
    Assert-True (!(Test-ListenersReady @($Endpoints[0]) $PID $Ports '127.0.0.1')) 'One ready port is insufficient'
    Assert-True (!(Test-ListenersReady @($Endpoints[0], $Endpoints[0]) $PID $Ports '127.0.0.1')) 'Duplicate rows cannot substitute for the second port'
    Assert-True (!(Test-ListenersReady $Candidates ($PID + 1) $Ports '127.0.0.1')) 'Reachable ports owned by another PID are rejected'
    Assert-True (!(Test-ListenersReady $Candidates $PID $Ports '127.0.0.2')) 'Listeners on another bind address are rejected'
    Assert-True (Test-TcpListener '::ffff:127.0.0.1' $Ports[0]) 'IPv4-mapped IPv6 addresses are probed as IPv4'
    $BoundPorts = @($Ports[0], $Bound.LocalEndPoint.Port)
    $BoundEndpoints = @($Endpoints[0], (New-Endpoint $BoundPorts[1] $PID))
    Assert-True (!(Test-ListenersReady $BoundEndpoints $PID $BoundPorts '127.0.0.1')) 'A bound socket that does not listen is rejected'

    $Http.Stop()
    Assert-True (!(Test-ListenersReady $Candidates $PID $Ports '127.0.0.1')) 'A stale endpoint row cannot pass after its socket closes'

    $Wildcard = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Any, 0)
    try {
        $Wildcard.Start()
        Assert-True (Test-TcpListener '0.0.0.0' $Wildcard.LocalEndpoint.Port) 'An IPv4 wildcard listener is probed through loopback'
    } finally { $Wildcard.Stop() }

    if ([Net.Sockets.Socket]::OSSupportsIPv6) {
        $Wildcard6 = [Net.Sockets.TcpListener]::new([Net.IPAddress]::IPv6Any, 0)
        try {
            $Wildcard6.Start()
            Assert-True (Test-TcpListener '::' $Wildcard6.LocalEndpoint.Port) 'An IPv6 wildcard listener is probed through IPv6 loopback'
        } finally { $Wildcard6.Stop() }
    }

    $script:QueryFails = $true
    $QueryFailed = $false
    try { $null = Get-ListenerCandidates } catch { $QueryFailed = $true }
    Assert-True $QueryFailed 'TCP query failures propagate instead of being treated as empty results'
} finally {
    $Http.Stop()
    $Socks.Stop()
    $Bound.Dispose()
}
