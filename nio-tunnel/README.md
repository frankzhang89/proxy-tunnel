# nio-tunnel

A high-performance NIO-based HTTP and SOCKS5 proxy using Netty that forwards traffic to an upstream HTTPS proxy. Ideal for environments where direct HTTPS proxy configuration is not supported.

## Features
- **Non-blocking I/O** - Built on Netty for high-performance async networking
- **HTTP Proxy** - Supports CONNECT (HTTPS tunneling) and regular HTTP forwarding
- **SOCKS5 Proxy** - Full SOCKS5 protocol support with optional authentication
- **PAC File** - Auto-generates or serves custom Proxy Auto-Config files
- **TLS** - Secure communication with upstream HTTPS proxy
- Configuration via `config.properties` with CLI overrides (e.g. `--listen.port=9999`)
- Optional Basic authentication on both inbound listener and upstream proxy
- Runnable fat JAR created with Maven Shade

## Quick Start
1. Adjust `src/main/resources/config.properties` or place a custom file next to the JAR.
2. Build the executable JAR:
   ```bash
   mvn -pl nio-tunnel clean package
   ```
3. Run it:
   ```bash
   java -jar target/nio-tunnel-1.0-SNAPSHOT.jar --upstream.host=myproxy.company.com
   ```

## Configuration
| Property | Description | Default |
| --- | --- | --- |
| `listen.host` | Local bind address | `127.0.0.1` |
| `listen.port` | HTTP proxy port | `8383` |
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

CLI flags mirror property names using `--key=value`. `--config=path` loads an extra properties file after the defaults.

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
