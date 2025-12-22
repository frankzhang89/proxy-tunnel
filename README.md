# Proxy Tunnel

Multi-module Java proxies that forward local HTTP(S) traffic through an upstream HTTPS proxy. Pick the module that fits your needs:
- [`simple-tunnel`](simple-tunnel/README.md): lightweight HTTP proxy using Java 21 virtual threads.
- [`nio-tunnel`](nio-tunnel/README.md): high-performance Netty-based HTTP & SOCKS5 proxy with PAC support.

## Requirements
- JDK 21+
- Maven 3.8+

## Build
Run Maven from the module you want:
```bash
mvn -pl simple-tunnel clean package
mvn -pl nio-tunnel clean package
```
Artifacts land under each module's `target/` (fat JAR via Maven Shade).

## Quick Start
### simple-tunnel (HTTP only)
1) Adjust `simple-tunnel/src/main/resources/config.properties` (or place a file next to the JAR).
2) Build, then run:
```bash
java -jar simple-tunnel/target/simple-tunnel-1.0-SNAPSHOT.jar --upstream.host=myproxy.company.com
```
3) Point your IDE/browser at `http://127.0.0.1:8282` (set auth if `listen.username`/`listen.password` is configured).

### nio-tunnel (HTTP + SOCKS5 + PAC)
1) Update `nio-tunnel/src/main/resources/config.properties` as needed.
2) Build, then run:
```bash
java -jar nio-tunnel/target/nio-tunnel-1.0-SNAPSHOT.jar --upstream.host=myproxy.company.com
```
3) Use `http://127.0.0.1:8383` for HTTP proxy, `127.0.0.1:1080` for SOCKS5, PAC at `http://127.0.0.1:8383/proxy.pac`.

## Configuration Highlights
Common properties (both modules) live in each module's `config.properties` and support CLI overrides (`--key=value`). Key fields:
- `listen.host` / `listen.port`: local bind address and port.
- `upstream.host` / `upstream.port`: required upstream HTTPS proxy location.
- `upstream.tls`: enable TLS to the upstream (default true).
- Optional Basic auth on listener and upstream via `*.username`/`*.password`.

Module-specific:
- `simple-tunnel`: timeouts, buffer size, log level, server name.
- `nio-tunnel`: SOCKS5 listener port, HTTP max header size, PAC options, Netty timeouts.

## Choosing a Module
- Use `simple-tunnel` when you need a small HTTP-only bridge for JetBrains IDEs.
- Use `nio-tunnel` for higher concurrency, SOCKS5 support, and PAC generation/serving.

## Repository Layout
- [`simple-tunnel/`](simple-tunnel/README.md) – virtual-thread HTTP proxy and docs.
- [`nio-tunnel/`](nio-tunnel/README.md) – Netty-based HTTP+SOCKS5 proxy and docs.
- `LICENSE` – project license.

## License
See `LICENSE`.
