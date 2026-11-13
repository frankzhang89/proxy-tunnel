# Release Notes

Release history for Proxy Tunnel, listed from newest to oldest. Add an entry for
each new release before creating its version tag.

## v0.2.0

### Fixed

- **Tunnel backpressure:** pause reads from the sending side when the receiving
  side's write buffer becomes congested, and resume reads when it drains. This
  applies to HTTP CONNECT, SOCKS tunnels, direct tunnels, and WebSocket relays.
- **SOCKS4/4a authentication bypass:** reject SOCKS4/4a requests when local
  username/password authentication is required, before opening any direct or
  upstream connection. Clients requiring authentication must use SOCKS5.
  SOCKS4/4a remain available when local authentication is disabled.
- **Lost initial tunnel data:** preserve target-server bytes received together
  with an upstream CONNECT success response. Install relays before removing
  protocol handlers and defer HTTP codec removal until the current decode cycle
  completes. The fix covers HTTP, SOCKS4/4a, and SOCKS5 upstream CONNECT paths.
- **CONNECT cleanup:** close both sides when forwarding the success response
  fails, and return a failure response if the upstream disconnects during the
  handshake. Record tunnel access logs only once, including initial relayed data.
- **Windows startup readiness:** require accepting TCP listeners owned by the
  launched process, including providers that report live listeners with
  `State=0`. Reject bound-only sockets and stale endpoint records.

### Changed

- Extracted the shared upstream CONNECT handshake, relay handoff, cleanup, and
  traffic accounting into a common handler. HTTP and SOCKS handlers retain their
  protocol-specific response encoding and client pipeline cleanup.
- Aligned both modules' Maven versions with the `v0.2.0` release tag.

### Validation

- Passed all 66 Java tests, including authentication, backpressure, fragmented
  and coalesced CONNECT responses, binary data integrity, and access logging.
- Verified real-socket and local TLS upstream scenarios, including slow receivers
  and success responses sent together with initial tunnel data.
- GitHub Actions built and tested both modules with Java 21 and published
  `nio-tunnel.zip` and `simple-tunnel.zip`.

## v0.1.0

- Initial packaged release of the `simple-tunnel` and `nio-tunnel` modules.
- Established Maven builds and runnable JAR packaging, with configuration files,
  launch scripts, and usage documentation.
- Added tag-triggered GitHub Actions builds and publication of complete ZIP
  distributions to GitHub Releases.
