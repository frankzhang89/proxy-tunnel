# Release Notes

Release history for Proxy Tunnel, listed from newest to oldest. Add an entry for
each new release before creating its version tag.

## v0.3.0

### Added

- **Local Web monitor (`nio-tunnel`):** a read-only dashboard at
  `http://127.0.0.1:8390`, enabled by default and restricted to loopback addresses.
  HTML, CSS, and JavaScript are bundled in the runnable JAR with no external CDN.
  A monitor bind failure does not prevent the HTTP/SOCKS proxy from starting.
- **Connection visibility:** show source IP/port, process name and PID, protocol,
  requested destination host/IP and port, actual `direct`/`upstream` route, and
  connection status. The configured upstream appears once beside the listener
  ports. Click a row for full addresses, timestamps, byte totals, and diagnostic
  details. HTTP keep-alive transfers are tracked separately; WebSocket upgrades
  retain their transfer record.
- **Best-effort process attribution:** identify local TCP socket owners using
  Windows IP Helper tables through JNA or Linux `ss`. Remote clients retain source
  IP/port information; unavailable process details do not interrupt forwarding.
  Successful names use a shared Guava cache with a default 300-second lifetime,
  a 4096-entry limit, and process identity checks to guard against PID reuse.
  `ui.process.name.cache.seconds` configures expiry; `0` disables name caching.
- **Live traffic statistics:** per-transfer and global upload/download speeds,
  sampled every second, plus session byte totals. Counters include connection
  I/O such as TLS, HTTP, and proxy handshakes, excluding TCP/IP headers,
  retransmissions, and monitor traffic. Ended/failed transfers remain in memory
  for five minutes, up to 1000 records; session totals survive history eviction.
- **Display controls:** one persistent Units selector offers Auto, B, KiB, MiB,
  and GiB for speeds and totals. Search and filter by route/protocol, pause and
  resume refresh, and retain the last successful data when updates fail.
- **Task Manager-inspired connection table:** separate Process Name/PID columns,
  sortable headings across all matching records before pagination, numeric
  IP-only source sorting, draggable column widths, and ellipsis/tooltips for
  truncated content. Widths survive data updates and reset on page reload.
  Rows per page offers 25, 50 (default), 100, and 200 records.
- **Read-only snapshot API:** `GET /api/snapshot` supports filtering, sorting,
  and pagination, exposes raw byte/B/s values and global upstream metadata, and
  keeps global summaries independent of the selected page or filter.
- **Version-specific GitHub Release notes:** starting with v0.3.0, tagged builds
  validate both Maven versions and require a unique, non-empty matching section
  in this file, then publish that section as the Release body alongside the ZIPs.
  Re-runs synchronize the same tag's body. Earlier versions still publish using
  their existing behavior, without backfilling or replacing legacy Release text.

### Changed

- Added `ui.*` settings to the existing configuration-file and CLI override
  workflow. Disabling `ui.enabled` also disables sampling and process lookup.
- Startup summaries include the configured monitor URL; runtime logs report
  whether its listener actually started. Proxy readiness still depends on the
  HTTP and SOCKS listeners.
- Aligned both modules' Maven versions with the `v0.3.0` release.

### Validation

- Passed all 93 Java tests on Windows, including existing proxy regressions and
  the new monitoring, process-name cache, ordering, and pagination coverage.
- Verified monitoring, caching, and sorting on Rocky Linux WSL, including real
  socket transfers and local process attribution.
- Passed browser checks for sorting, keyboard controls, every resize handle,
  truncation, pagination choices, paused interactions, stale-response handling,
  failure rollback, reload defaults, unit preferences, and narrow layouts.
- Verified the runnable JAR's real Web page/API and content-security-policy
  compatibility, plus Windows startup-readiness regression checks.

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
