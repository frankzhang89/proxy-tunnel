# nio-tunnel

A high-performance NIO-based HTTP and SOCKS5 proxy using Netty that forwards traffic to an upstream HTTPS proxy. Ideal for environments where direct HTTPS proxy configuration is not supported.

## Features
- **Non-blocking I/O** - Built on Netty for high-performance async networking
- **HTTP Proxy** - Supports CONNECT (HTTPS tunneling) and regular HTTP forwarding
- **Streaming HTTP** - Streams request and response bodies, including large uploads/downloads, chunked trailers, `100 Continue`, and SSE; response bodies are not limited by `http.maxInitialBytes`
- **WebSocket Proxy** - Supports `ws://` HTTP Upgrade and `wss://` tunneling
- **SOCKS Proxy** - SOCKS5 TCP CONNECT with optional username/password authentication; SOCKS4/4a CONNECT when local authentication is disabled
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
  On Windows, readiness also requires a successful local TCP connection to each
  listener. The port query uses unconnected local sockets rather than filtering
  by `State=Listen`, because some Windows TCP providers return `State=0` for live
  listeners. A socket that is only bound, without accepting connections, cannot
  pass the readiness check.
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

Run the Windows readiness regression tests without starting Java:
`powershell.exe -NoProfile -ExecutionPolicy Bypass -File nio-tunnel/src/test/powershell/StartupReadinessTest.ps1`.

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
When `listen.username` is set, local authentication is required: SOCKS4/4a requests
are rejected before any direct or upstream connection is opened. Use a SOCKS5
client with the configured username and password. SOCKS4 USERID is not treated
as an authentication credential. Without local authentication, SOCKS4/4a remain
available on the same listener. Only TCP CONNECT is supported; BIND and UDP
ASSOCIATE are not supported.

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

## Local Web monitor

Open `http://127.0.0.1:8390` after startup. The read-only page is bundled in the
runnable JAR and needs no external assets. It shows source IP/port, best-effort
local PID/name, protocol, requested destination host/IP and port, actual
`upstream`/`direct` route, upstream endpoint, status, upload/download speed and
cumulative bytes. Click a row for details. Remote clients show source IP/port.
Process names identify the socket owner (which may be a browser subprocess or
`java`), not necessarily a product name. Destination domains are not inferred
from IP addresses or resolved on behalf of the upstream.

| Property | Default | Purpose |
| --- | --- | --- |
| `ui.enabled` | `true` | Enable monitoring; false disables sampling and process lookup too |
| `ui.host` | `127.0.0.1` | Loopback addresses only |
| `ui.port` | `8390` | Separate Web listener |
| `ui.history.seconds` | `300` | Retain ended/failed transfers in memory |
| `ui.history.max.entries` | `1000` | Maximum ended/failed records |
| `ui.process.lookup.enabled` | `true` | Best-effort local process lookup |
| `ui.process.name.cache.seconds` | `300` | Successful PID/name cache lifetime; 0 disables the cache |

These settings follow the existing file/CLI precedence, e.g.
`--ui.port=8391` or `--ui.enabled=false`. If the UI cannot bind, the proxy keeps
running; the runtime log records whether the configured UI address is available.

Statistics are sampled once per second. **Refresh every** controls how often
the page fetches the current snapshot, with a default of 1 second. Pick a preset
or enter a positive whole number of seconds. Upload means tunnel to
upstream/direct target; download is the reverse.
Counters observe successful Netty writes and received bytes near the outbound
socket, including TLS/HTTP/proxy handshakes, excluding TCP/IP headers, retransmits,
and the monitor's own traffic. Partially failed writes are not estimated. These
are connection I/O counters, not NIC counters. Totals include short-lived and
ended transfers, survive history eviction, and reset on restart. A client TCP
connection can produce multiple HTTP transfer rows; WebSocket upgrades retain
one row. Activity counts include connecting transfers. Filtering never changes
global totals. Pause affects page refresh only. Closed rows show zero speed.

**Traffic speed** shows global upload (blue) and download (green) over the last
1, 5 or 10 minutes. Hover over a sample for its local time and both speeds.
The chart shares the page refresh setting and retains one-second resolution
even at longer refresh intervals. Pause freezes the displayed chart. On resume,
the chart loads the available history. The server keeps up to 600 samples from
the past 10 minutes in memory, starting fresh when it restarts.

Windows uses JNA IP Helper tables; Linux uses `ss` (iproute2). Missing tools,
permissions, namespaces/NAT, very short connections, or native loading failures
can make attribution unavailable. Lookup is shared between pages, bounded and
runs outside proxy I/O threads. No credentials, payloads, full URLs or command
lines are exposed by the monitor. Newer JDKs may require
`--enable-native-access=ALL-UNNAMED` to allow JNA without native-access warnings.

The JSON interface is `GET /api/snapshot`, with `scope=active|recent|all`, literal
`q` search, `route=all|upstream|direct`,
`protocol=all|http|http_connect|socks4|socks5|websocket`, `page` and `pageSize`
(default 50, maximum 200). Add `traffic=1` to include `trafficHistory`, ordered
by `sampleTime` (epoch milliseconds), with `uploadBps` and `downloadBps` per
sample. Speeds use B/s and cumulative sizes use bytes.

### Process name caching and display units

Process names are shared across connections using a bounded Guava cache (4096
successful entries). Entries expire 300 seconds after writing by default; hits
do not extend this deadline. The next lookup reloads an expired entry. Set
`--ui.process.name.cache.seconds=60` to change the lifetime, or `0` to disable
name caching. Negative values are configuration errors. Missing names and lookup
failures are not cached. Process identity is checked on every cache hit to avoid
reusing another process's name after PID reuse. Socket-to-PID matching still uses
fresh connection tables; existing connection records retain their resolved name.

The English UI displays the configured upstream once in the upper-right corner,
next to the listener ports, including when there are no connections or only
direct transfers. `direct`/`upstream` route labels remain in the table. Snapshot
JSON adds a top-level `upstream` object (`host`, `port`), retaining the per-record
fields for compatibility.

The single **Units** selector applies to all speeds and byte totals, including
row details. **Auto** uses powers of 1024 up to GiB; B, KiB, MiB and GiB force a
fixed unit (with `/s` appended for speeds). Bytes are integers; larger units use
two decimal places. The browser remembers the selection when local storage is
available. Unit changes work while refresh is paused and never affect raw JSON
counters or server-side sampling. Times use English formatting in the browser's
local timezone. Process names and addresses are displayed as supplied.

### Connection table controls

The table separates **Process Name** and **PID**. Click any column heading to
sort all matching transfers before pagination; click it again to reverse order.
Text/address/PID columns initially sort ascending, speeds descending. Missing
values stay last. Source addresses sort numerically by IP only, ignoring source
ports; speed sorting always uses raw B/s regardless of display units. Equal keys
use numeric connection IDs for stable ordering. Before a heading is selected,
the existing time-descending order applies.

Drag the right edge of any heading to resize that column (minimum 48px).
Widths survive automatic updates, sorting, filtering and pagination, but reset
on browser reload. Narrow cells and headings truncate with an ellipsis; hover
for full text, or click a row to view details. The compact list uses a light
Task Manager-inspired style while retaining the overview cards.

**Rows per page** offers 25, 50 (default), 100 and 200. Changing it resets to page
one while preserving filters, ordering, units and column widths. This preference
is not stored across page reloads. Sorting and page-size changes still perform a
single request while automatic refresh is paused. Failed requests retain the
last successful data and restore the last confirmed controls. Superseded replies
cannot overwrite newer selections.

Snapshot requests optionally accept `sort=processName|pid|sourceIp|protocol|target|route|uploadBps|downloadBps|status`
and `order=asc|desc`. Invalid values return 400. Omit `sort` for default time
ordering. Existing `page` and `pageSize` parameters continue to work; size is
capped at 200 and summaries remain global.

Backend ordering/pagination tests run with `mvn test -DskipTests=false`. The browser fixture
suite is `node nio-tunnel/src/test/web/connection-table.cjs` from the repository
root, with Playwright available through `NODE_PATH` or `PLAYWRIGHT_MODULE`.
Optionally set `CHROME_EXECUTABLE` to an installed Chrome executable; otherwise
Playwright uses its installed Chromium. This verifies interactions with fixture
responses; Java tests verify the real snapshot ordering.
