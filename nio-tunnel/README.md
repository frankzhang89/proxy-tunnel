# nio-tunnel

A high-performance NIO-based HTTP and SOCKS5 proxy using Netty that forwards traffic to an upstream HTTPS proxy. Ideal for environments where direct HTTPS proxy configuration is not supported.

## Features
- **Non-blocking I/O** - Built on Netty for high-performance async networking
- **HTTP Proxy** - Supports CONNECT (HTTPS tunneling) and regular HTTP forwarding
- **Streaming HTTP** - Streams request and response bodies, including large uploads/downloads, chunked trailers, `100 Continue`, and SSE; response bodies are not limited by `http.maxInitialBytes`
- **WebSocket Proxy** - Supports `ws://` HTTP Upgrade and `wss://` tunneling
- **SOCKS5 Proxy** - Full SOCKS5 protocol support with optional authentication
- **PAC File** - Auto-generates or serves custom Proxy Auto-Config files
- **TLS** - Secure communication with upstream HTTPS proxy
- **No Proxy** - Bypass the upstream proxy and connect directly to specified hosts
- Configuration via `config.properties` with CLI overrides (e.g. `--listen.port=9999`)
- Optional Basic authentication on both inbound listener and upstream proxy
- Runnable fat JAR created with Maven Shade

## Quick Start
1. Adjust `src/main/resources/config.properties` or place `config.properties` in
   the working directory (the script directory when using the lifecycle scripts).
2. Build the executable JAR:
   ```bash
   mvn -f nio-tunnel/pom.xml clean package
   ```
3. Run it:
   ```bash
   java -jar D:/opt/packaging/nio-tunnel/nio-tunnel.jar --upstream.host=myproxy.company.com
   ```

The current POM writes the runnable fat JAR to `${base.export.path}/nio-tunnel/nio-tunnel.jar`
(default `D:/opt/packaging/nio-tunnel/nio-tunnel.jar`). The versioned JAR in `target/`
is not the runnable fat JAR. On Linux, set an appropriate output directory, for example:
`mvn -f nio-tunnel/pom.xml clean package -Dbase.export.path=/opt/packaging`.

## Lifecycle scripts

Use `startup.bat` on Windows or `startup.sh` on Linux. Both accept `start`, `stop`,
and `restart`; the default action is `restart`. Double-clicking the BAT (or running
it without arguments in an interactive console) opens a vertical menu: `1` restarts,
`2` stops, and `3` starts. The selected row has a highlighted background. Arrow and
number keys select an action without stopping or resetting the five-second countdown;
Enter executes immediately. At timeout, the selected action runs (initially `restart`).
Explicit actions and invocations with config
arguments skip the menu; redirected input also defaults directly to `restart`.
To customize the highlighted row, edit `$SelectionBackground` and
`$SelectionForeground` in `Select-Action` in `startup.ps1` (defaults: `DarkCyan`
background and `White` text).
Copy the runnable JAR beside the scripts as `nio-tunnel.jar`, or customize its path.
Keep `StartupConfig.java` with both launchers, and `startup.ps1` with the BAT.
The configuration helper runs in Java source mode and calls the JAR's own config
loader, so a **JDK 21+**, rather than only a JRE, is required.

```bat
startup.bat start --upstream.host=proxy.example.com --listen.port=8381
startup.bat restart --config="C:\configs\production.properties"
startup.bat stop
startup.bat --upstream.host=proxy.example.com
```

```bash
chmod +x startup.sh
./startup.sh start --upstream.host=proxy.example.com --listen.port=8381
./startup.sh restart --config=/etc/nio-tunnel/production.properties
./startup.sh stop
./startup.sh --upstream.host=proxy.example.com
```

- Java selection: `NIO_TUNNEL_JAVA` (full executable path), then `JAVA_HOME/bin/java`,
  then `java` on `PATH`. Uncomment the custom-path settings in the BAT or Bash script
  to override Java and/or the JAR. `NIO_TUNNEL_JAR` also overrides the JAR path.
- Configuration precedence, from lowest to highest: bundled `config.properties`,
  script-directory `config.properties`, the file selected by `--config=PATH`, then
  `--key=value` arguments. Relative paths resolve against the script directory.
  The bundled HTTP port is currently **8380**; SOCKS uses **1080**.
- `start` prints the existing PID without starting another instance. `restart`
  stops the managed process first; a missing process is informational and does
  not prevent startup. Each invocation must supply the desired overrides again.
- PID and identity files live beside the scripts (`nio-tunnel.pid`, plus
  `.pid.json` on Windows or `.pid.meta` on Linux). Process identity is verified
  before termination. An operation lock prevents simultaneous lifecycle commands.
- Both effective listener ports are checked before launch. Conflicts print the
  owner PID, name, and executable path where permissions allow; conflicting
  wildcard listeners are treated conservatively. Linux requires Bash 4.4+,
  `flock` (util-linux), `ss` (iproute2), and `/proc`. Without permission to inspect
  another user's sockets, Linux may report owner details as unavailable; run with
  appropriate privileges to see them.
- Startup succeeds only when the new PID owns both listeners. The summary includes
  PID, ports, upstream, and log paths; it does not test upstream connectivity.
  SLF4J runtime messages (startup, info, warnings, and logged exceptions) and stdout
  go to `logs/nio-tunnel.log`; raw stderr, including uncaught exceptions, goes to
  `logs/nio-tunnel-error.log`. Both are overwritten on each start. The access log
  still follows `access.log.file` and appends to the existing file. Launchers default
  to `--access.log.console=false` to avoid duplicating access records in the runtime
  log; explicitly passing `--access.log.console=true` restores console mirroring.
- Success waits five seconds or a keypress; failure waits for a keypress.
  With redirected input, failure exits immediately and success waits five seconds.
  Java stays in the background. Existing terminals stay open; a double-clicked
  BAT window closes when the script exits. Failures return a nonzero exit code.
- Linux stop sends SIGTERM and waits up to 15 seconds, without forcing SIGKILL.
  Windows stop terminates the process and cannot guarantee Java shutdown hooks.
  A failed stop prevents a restart. Scripts manage only their recorded process,
  never unrelated processes occupying a port.

In the WSL environment used for validation, Netty with the installed JDK 25 needed
`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true` to bind IPv4 listeners. If the
same `NioSocketError` occurs there, set this environment variable for IPv4 operation.

## Configuration
| Property | Description | Default |
| --- | --- | --- |
| `listen.host` | Local bind address | `127.0.0.1` |
| `listen.port` | HTTP proxy port | `8380` (bundled config) |
| `listen.socks.port` | SOCKS5 proxy port | `1080` |
| `listen.username`/`listen.password` | Require Basic auth from clients | empty (disabled) |
| `upstream.host` | **Required** HTTPS proxy hostname | (none) |
| `upstream.port` | Upstream proxy port | `443` |
| `upstream.tls` | Use TLS to talk to upstream | `true` |
| `upstream.username`/`upstream.password` | Basic auth for upstream | empty (disabled) |
| `connect.timeout.millis` | Upstream connect timeout | `5000` |
| `http.maxInitialBytes` | Max HTTP request header bytes | `1048576` |
| `pac.enabled` | Enable PAC file serving | `true` |
| `pac.path` | PAC file URL path | `/proxy.pac` |
| `pac.host` | Host in generated PAC file | `127.0.0.1` |
| `pac.file` | Path to custom PAC file | empty (auto-generate) |
| `server.name` | Name shown in responses | `nio-tunnel` |
| `no.proxy.hosts` | Comma-separated hosts/IPs that should bypass the upstream proxy and connect directly | empty |

CLI flags mirror property names using `--key=value`. `--config=path` loads an extra
properties file after the working-directory config. Files override only properties
they provide; CLI values have the highest priority. For direct `java -jar` launches,
the working directory, rather than the JAR's location, determines which
`config.properties` is automatically loaded.

## Architecture
```
┌─────────────────────────────────────────────────────────────┐
│                      NIOProxyTunnel                          │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐         ┌─────────────────┐           │
│  │  HTTP Listener  │         │ SOCKS5 Listener │           │
│  │   (port 8383)   │         │   (port 1080)   │           │
│  └────────┬────────┘         └────────┬────────┘           │
│           │                           │                     │
│  ┌────────▼────────┐         ┌────────▼────────┐           │
│  │HttpProxyHandler │         │  Socks5Handler  │           │
│  └────────┬────────┘         └────────┬────────┘           │
│           │                           │                     │
│           └───────────┬───────────────┘                     │
│                       │                                     │
│              ┌────────▼────────┐                            │
│              │  Upstream HTTPS  │                           │
│              │   Proxy (TLS)    │                           │
│              └──────────────────┘                           │
└─────────────────────────────────────────────────────────────┘
```

## Usage Examples

### HTTP Proxy
Configure your application to use HTTP proxy at `http://127.0.0.1:8383`:
```bash
export http_proxy=http://127.0.0.1:8383
export https_proxy=http://127.0.0.1:8383
```

### SOCKS5 Proxy
Configure your application to use SOCKS5 proxy at `127.0.0.1:1080`:
```bash
# For curl
curl --socks5 127.0.0.1:1080 https://example.com

# For git
git config --global http.proxy socks5://127.0.0.1:1080
```

### PAC File
Access the auto-generated PAC file at:
```
http://127.0.0.1:8383/proxy.pac
```

Configure browsers or system proxy settings to use this PAC URL for automatic proxy configuration.

## JetBrains IDE Setup
1. Start nio-tunnel.
2. In IDE: Settings → Appearance & Behavior → System Settings → HTTP Proxy.
3. Choose **Manual proxy configuration**, enter `http://127.0.0.1:8383` (or your custom host/port).
4. If you set `listen.username`/`listen.password`, enter them under Proxy authentication.

## Browser Setup (via PAC)
1. Start nio-tunnel.
2. In browser proxy settings, select "Automatic proxy configuration URL".
3. Enter: `http://127.0.0.1:8383/proxy.pac`

## Notes
- The proxy forwards both CONNECT and regular HTTP requests through the upstream HTTPS proxy.
- SOCKS5 CONNECT commands are translated to HTTP CONNECT when communicating with upstream.
- The JVM trust store controls HTTPS verification. Customize via standard `javax.net.ssl.trustStore` flags if needed.
- For troubleshooting, check console output or configure SLF4J logging.

## Comparison with simple-tunnel

| Feature | simple-tunnel | nio-tunnel |
| --- | --- | --- |
| I/O Model | Virtual Threads (JDK 21) | Netty NIO |
| HTTP Proxy | ✓ | ✓ |
| SOCKS5 Proxy | ✗ | ✓ |
| PAC File | ✗ | ✓ |
| Default HTTP Port | 8282 | 8383 |
