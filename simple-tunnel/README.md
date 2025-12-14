# simple-tunnel

A lightweight HTTP listener that forwards traffic to an HTTPS proxy, enabling JetBrains IDEs to work with HTTPS-only corporate proxies.

## Features
- Java 21 virtual threads for highly concurrent proxying
- Configuration via `config.properties` with CLI overrides (e.g. `--listen.port=9999`)
- Optional Basic authentication on both inbound listener and upstream proxy
- Runnable fat JAR created with Maven Shade

## Quick Start
1. Adjust `src/main/resources/config.properties` or place a custom file next to the JAR.
2. Build the executable JAR:
   ```bash
   mvn -pl simple-tunnel clean package
   ```
3. Run it:
   ```bash
   java -jar target/simple-tunnel-1.0-SNAPSHOT.jar --upstream.host=myproxy.company.com
   ```

## Configuration
| Property | Description | Default |
| --- | --- | --- |
| `listen.host` | Local bind address | `127.0.0.1` |
| `listen.port` | Local port JetBrains will talk to | `8282` |
| `listen.username`/`listen.password` | Require Basic auth from IDE | empty (disabled) |
| `upstream.host` | **Required** HTTPS proxy hostname | (none) |
| `upstream.port` | Upstream proxy port | `443` |
| `upstream.tls` | Use TLS to talk to upstream | `true` |
| `upstream.username`/`upstream.password` | Basic auth for upstream | empty (disabled) |
| `timeouts.connectMillis` | Upstream connect timeout | `10000` |
| `timeouts.readMillis` | Socket read timeout | `60000` |
| `buffer.size` | Pipe buffer size | `16384` |
| `header.maxBytes` | Max request/response header bytes | `32768` |
| `log.level` | `ERROR`/`WARN`/`INFO`/`DEBUG` | `INFO` |
| `server.name` | Name shown in responses | `simple-tunnel` |

CLI flags mirror property names using `--key=value`. `--config=path` loads an extra properties file after the defaults.

## JetBrains Setup
1. Start simple-tunnel.
2. In IDE: Settings → Appearance & Behavior → System Settings → HTTP Proxy.
3. Choose **Manual proxy configuration**, enter `http://127.0.0.1:8282` (or your custom host/port).
4. If you set `listen.username`/`listen.password`, enter them under Proxy authentication.

## Notes
- The proxy currently forwards standard HTTP requests via tunnel; CONNECT requests can be added similarly.
- The JVM trust store controls HTTPS verification. Customize via standard `javax.net.ssl.trustStore` flags if needed.
- For troubleshooting, run with `--log.level=DEBUG` and watch stdout.

