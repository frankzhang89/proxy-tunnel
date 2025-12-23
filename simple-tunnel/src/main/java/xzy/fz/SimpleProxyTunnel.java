package xzy.fz;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SimpleProxyTunnel - A lightweight HTTP proxy forwarder written in pure Java (JDK 21+).
 * 
 * <p>This application acts as a local proxy server that forwards HTTP requests to an upstream
 * proxy server. It's useful for scenarios where you need to chain proxies or add authentication
 * to an existing proxy connection.</p>
 * 
 * <h2>Architecture Overview:</h2>
 * <pre>
 * [Client] --HTTP--> [SimpleProxyTunnel:8282] --HTTP/HTTPS--> [Upstream Proxy] ---> [Target Server]
 * </pre>
 * 
 * <h2>Key Features:</h2>
 * <ul>
 *   <li>Virtual threads (Project Loom) for efficient concurrent connection handling</li>
 *   <li>Optional Basic authentication for both client and upstream proxy</li>
 *   <li>TLS support for upstream proxy connections</li>
 *   <li>Configurable via properties file or command-line arguments</li>
 *   <li>Graceful shutdown with proper resource cleanup</li>
 * </ul>
 * 
 * <h2>Data Flow:</h2>
 * <ol>
 *   <li>Client connects to local proxy (default: 127.0.0.1:8282)</li>
 *   <li>Proxy reads and parses HTTP request headers</li>
 *   <li>Authenticates client if configured</li>
 *   <li>Opens connection to upstream proxy (with optional TLS)</li>
 *   <li>Forwards request with modified headers (adds upstream auth if needed)</li>
 *   <li>Bridges bidirectional data between client and upstream</li>
 * </ol>
 * 
 * @author xzy.fz
 * @since JDK 21 (requires virtual threads support)
 */
public  class SimpleProxyTunnel {
    
    /** Private constructor to prevent instantiation - this is a utility/main class */
    private SimpleProxyTunnel() {
    }

    /**
     * Application entry point.
     * 
     * <p>Startup sequence:</p>
     * <ol>
     *   <li>Parse command-line arguments</li>
     *   <li>Load configuration (classpath -> working dir -> CLI overrides)</li>
     *   <li>Validate required settings (upstream.host is mandatory)</li>
     *   <li>Start the tunnel server with shutdown hook for graceful termination</li>
     * </ol>
     * 
     * @param args Command-line arguments (use --help for available options)
     */
    public static void main(String[] args) {
        Cli cli = Cli.parse(args);
        if (cli.helpRequested()) {
            Cli.printHelp();
            return;
        }

        Config config = ConfigLoader.load(cli);
        Logger log = new Logger(config.logLevel());

        if (config.upstreamHost().isBlank()) {
            System.err.println("Missing required property upstream.host (set it in config.properties or via CLI)");
            System.exit(2);
        }

        log.info("Starting simple-tunnel %s", config);
        TunnelServer server = new TunnelServer(config, log);
        // Register shutdown hook for graceful termination (Ctrl+C, SIGTERM, etc.)
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "simple-tunnel-shutdown"));
        server.start();
    }

    /**
     * The main tunnel server that accepts client connections and forwards them upstream.
     * 
     * <p>Threading model: Uses JDK 21 virtual threads via {@link Executors#newVirtualThreadPerTaskExecutor()}
     * for lightweight, scalable concurrent connection handling. Each client connection is handled
     * in its own virtual thread, allowing thousands of simultaneous connections with minimal overhead.</p>
     */
    private static final class TunnelServer {
        private final Config config;                              // Immutable configuration
        private final Logger log;                                 // Logger instance
        private final AtomicBoolean running = new AtomicBoolean(); // Thread-safe running state flag
        private volatile ServerSocket serverSocket;               // Volatile for visibility across threads

        TunnelServer(Config config, Logger log) {
            this.config = config;
            this.log = log;
        }

        /**
         * Starts the tunnel server and begins accepting client connections.
         * 
         * <p>Uses a virtual thread executor (try-with-resources ensures proper shutdown).
         * The accept loop runs until {@link #stop()} is called or an error occurs.</p>
         * 
         * <p>Note: compareAndSet ensures thread-safe single startup - prevents double-start.</p>
         */
        void start() {
            if (!running.compareAndSet(false, true)) {
                return;
            }

            // Virtual thread executor - each task runs in its own lightweight virtual thread
            // Try-with-resources ensures executor shutdown when server stops
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                ServerSocket localSocket = new ServerSocket();
                this.serverSocket = localSocket; // Store for stop() to close
                try (localSocket) {
                    // Bind to configured host:port (default 127.0.0.1:8282)
                    InetAddress bindAddress = InetAddress.getByName(config.listenHost());
                    localSocket.bind(new InetSocketAddress(bindAddress, config.listenPort()));
                    log.info("Listening on %s:%d", bindAddress.getHostAddress(), config.listenPort());

                    // Main accept loop - blocks on accept(), spawns virtual thread per connection
                    while (running.get()) {
                        Socket client = localSocket.accept(); // Blocking call
                        executor.submit(() -> handleClient(client)); // Handle in virtual thread
                    }
                } finally {
                    this.serverSocket = null;
                }
            } catch (IOException ioe) {
                if (running.get()) {
                    log.error(ioe, "Server error: %s", ioe.getMessage());
                }
            }
        }

        /**
         * Gracefully stops the tunnel server.
         * 
         * <p>Closing the ServerSocket will cause the blocking accept() call to throw
         * an IOException, breaking the accept loop. The running flag check prevents
         * logging this expected exception as an error.</p>
         */
        void stop() {
            // compareAndSet ensures stop is only executed once
            if (!running.compareAndSet(true, false)) {
                return;
            }
            log.info("Shutting down listener");
            ServerSocket socket = this.serverSocket;
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }

        /**
         * Handles a single client connection.
         * 
         * <p>Processing steps:</p>
         * <ol>
         *   <li>Read and parse HTTP request headers from client</li>
         *   <li>Validate client authentication (if required)</li>
         *   <li>Open connection to upstream proxy</li>
         *   <li>Forward request with modified headers</li>
         *   <li>Bridge bidirectional data flow until connection closes</li>
         * </ol>
         * 
         * @param client The accepted client socket (will be closed when method returns)
         */
        private void handleClient(Socket client) {
            String clientId = client.getRemoteSocketAddress().toString(); // For logging
            log.debug("Accepted connection %s", clientId);

            try (client) { // try-with-resources ensures socket cleanup
                client.setSoTimeout(config.readTimeoutMillis()); // Read timeout for stuck connections
                BufferedInputStream clientIn = new BufferedInputStream(client.getInputStream());
                BufferedOutputStream clientOut = new BufferedOutputStream(client.getOutputStream());

                // Step 1: Parse HTTP request headers (reads until \r\n\r\n)
                HttpRequestHead request = HttpRequestHead.read(clientIn, config.headerMaxBytes());
                if (request == null) {
                    sendError(clientOut, "HTTP/1.1 400 Bad Request", "Malformed request", config.serverName());
                    return;
                }

                // Step 2: Client authentication check (if configured)
                if (config.requireClientAuth()) {
                    String supplied = request.firstHeaderValue("Proxy-Authorization");
                    if (supplied == null || !supplied.equals(config.expectedClientAuthHeader())) {
                        log.warn("Rejected unauthenticated request from %s", clientId);
                        sendProxyAuthRequired(clientOut, config.serverName()); // 407 response
                        return;
                    }
                }

                log.info("%s %s via %s", request.method(), request.target(), clientId);

                // Step 3: Connect to upstream proxy and forward the request
                try (Socket upstream = openUpstreamSocket()) {
                    upstream.setSoTimeout(config.readTimeoutMillis());
                    BufferedInputStream upstreamIn = new BufferedInputStream(upstream.getInputStream());
                    BufferedOutputStream upstreamOut = new BufferedOutputStream(upstream.getOutputStream());

                    // Step 4: Modify headers - remove client auth, add upstream auth if configured
                    List<Header> forwardedHeaders = filterAndAugmentHeaders(request.headers(), config);
                    // Reconstruct HTTP request line + headers in wire format
                    byte[] upstreamHead = HttpRequestHead.toWire(request.startLine(), forwardedHeaders);
                    upstreamOut.write(upstreamHead);
                    upstreamOut.flush();

                    // Step 5: Bridge bidirectional data flow (request body + response)
                    bridge(clientIn, clientOut, upstreamIn, upstreamOut);
                }
            } catch (IOException ioe) {
                log.warn(ioe, "Connection error: %s", ioe.getMessage());
            }
        }

        /**
         * Opens a socket connection to the upstream proxy.
         * 
         * @return Connected socket (plain or TLS based on config)
         * @throws IOException if connection fails or times out
         */
        private Socket openUpstreamSocket() throws IOException {
            // Choose socket factory based on TLS setting (default: TLS enabled)
            SocketFactory factory = config.upstreamTls() ? SSLSocketFactory.getDefault() : SocketFactory.getDefault();
            Socket upstream = factory.createSocket();
            // Connect with timeout to avoid hanging on unreachable hosts
            upstream.connect(new InetSocketAddress(config.upstreamHost(), config.upstreamPort()), config.connectTimeoutMillis());
            return upstream;
        }

        /**
         * Creates a bidirectional data bridge between client and upstream.
         * 
         * <p>Spawns two virtual threads:</p>
         * <ul>
         *   <li>"up" thread: pumps data from client → upstream (request body)</li>
         *   <li>"down" thread: pumps data from upstream → client (response)</li>
         * </ul>
         * 
         * <p>Both threads run concurrently and the method blocks until both complete.
         * This allows full-duplex communication (important for WebSocket upgrades, etc.)</p>
         */
        private void bridge(InputStream clientIn,
                             OutputStream clientOut,
                             InputStream upstreamIn,
                             OutputStream upstreamOut) {
            // Start two pump threads for bidirectional data flow
            Thread up = Thread.startVirtualThread(() -> pump(clientIn, upstreamOut));   // Client → Upstream
            Thread down = Thread.startVirtualThread(() -> pump(upstreamIn, clientOut)); // Upstream → Client
            
            // Wait for both directions to complete (connection closed or error)
            try {
                up.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt(); // Preserve interrupt status
            }
            try {
                down.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Pumps data from input stream to output stream until EOF or error.
         * 
         * <p>This is the core data transfer loop. It reads chunks of data and immediately
         * writes and flushes them to maintain low latency. IOException is silently caught
         * because connection closure is expected behavior (client disconnect, timeout, etc.)</p>
         * 
         * @param in Source stream to read from
         * @param out Destination stream to write to
         * @return Total bytes transferred
         */
        private long pump(InputStream in, OutputStream out) {
            byte[] buffer = new byte[config.bufferSize()]; // Default 16KB buffer
            long total = 0L;
            try {
                int read;
                // Read loop: -1 indicates EOF (clean connection close)
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    out.flush(); // Flush immediately for low latency
                    total += read;
                }
            } catch (IOException ignored) {
                // Connection tear-down is expected (RST, timeout, close); logging would be too noisy
            }
            return total;
        }

        /**
         * Filters and modifies request headers before forwarding upstream.
         * 
         * <p>Modifications:</p>
         * <ul>
         *   <li>Removes client's Proxy-Authorization (security: don't leak credentials)</li>
         *   <li>Adds upstream Proxy-Authorization if configured</li>
         *   <li>Adds Proxy-Connection: keep-alive for connection reuse</li>
         * </ul>
         * 
         * @param headers Original request headers from client
         * @param cfg Configuration containing upstream auth settings
         * @return Modified header list for upstream request
         */
        private List<Header> filterAndAugmentHeaders(List<Header> headers, Config cfg) {
            List<Header> filtered = new ArrayList<>();
            for (Header header : headers) {
                // Security: never forward client's proxy credentials to upstream
                if (header.nameEquals("Proxy-Authorization")) {
                    continue;
                }
                filtered.add(header);
            }
            // Add upstream authentication if configured
            if (cfg.expectedUpstreamAuthHeader() != null) {
                filtered.add(new Header("Proxy-Authorization", cfg.expectedUpstreamAuthHeader()));
            }
            // Request persistent connection to upstream proxy
            filtered.add(new Header("Proxy-Connection", "keep-alive"));
            return filtered;
        }

        /**
         * Sends an HTTP error response to the client.
         * 
         * @param clientOut Output stream to write response to
         * @param statusLine HTTP status line (e.g., "HTTP/1.1 400 Bad Request")
         * @param body Error message to display in HTML body
         * @param serverName Server name for Server header
         */
        private void sendError(OutputStream clientOut, String statusLine, String body, String serverName) throws IOException {
            String payload = "<html><body><h1>" + body + "</h1></body></html>";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            // Build HTTP response with proper headers
            String response = statusLine + "\r\n" +
                    "Date: " + HttpRequestHead.httpDate() + "\r\n" +
                    "Server: " + serverName + "\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: " + bytes.length + "\r\n" +
                    "Connection: close\r\n\r\n"; // Close connection after error
            clientOut.write(response.getBytes(StandardCharsets.ISO_8859_1)); // HTTP headers use ISO-8859-1
            clientOut.write(bytes); // Body uses UTF-8
            clientOut.flush();
        }

        /**
         * Sends HTTP 407 Proxy Authentication Required response.
         * 
         * <p>This prompts the client to provide Basic authentication credentials.
         * The Proxy-Authenticate header tells the client what auth scheme to use.</p>
         * 
         * @param clientOut Output stream to write response to
         * @param serverName Server name used as the authentication realm
         */
        private void sendProxyAuthRequired(OutputStream clientOut, String serverName) throws IOException {
            String response = "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                    "Date: " + HttpRequestHead.httpDate() + "\r\n" +
                    "Proxy-Authenticate: Basic realm=\"" + serverName + "\"\r\n" + // Tells client to use Basic auth
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n";
            clientOut.write(response.getBytes(StandardCharsets.ISO_8859_1));
            clientOut.flush();
        }
    }
    
    // ==================== Configuration Records ====================

    /**
     * Immutable configuration record holding all tunnel settings.
     * 
     * <p>Configuration is loaded by {@link ConfigLoader} from multiple sources
     * (in order of precedence): CLI args > config file > classpath defaults.</p>
     * 
     * @param listenHost Local bind address (default: 127.0.0.1)
     * @param listenPort Local listen port (default: 8282)
     * @param requireClientAuth Whether to require Basic auth from clients
     * @param expectedClientAuthHeader Pre-computed "Basic xxx" header to match
     * @param upstreamHost Upstream proxy hostname (REQUIRED)
     * @param upstreamPort Upstream proxy port (default: 443)
     * @param upstreamTls Whether to use TLS for upstream connection (default: true)
     * @param expectedUpstreamAuthHeader Pre-computed auth header to send upstream (or null)
     * @param connectTimeoutMillis TCP connect timeout (default: 10s)
     * @param readTimeoutMillis Socket read timeout (default: 60s)
     * @param bufferSize I/O buffer size for data pumping (default: 16KB)
     * @param headerMaxBytes Max bytes to read for HTTP headers (default: 32KB)
     * @param logLevel Logging verbosity
     * @param serverName Server name for HTTP headers
     */
    private record Config(
            String listenHost,
            int listenPort,
            boolean requireClientAuth,
            String expectedClientAuthHeader,
            String upstreamHost,
            int upstreamPort,
            boolean upstreamTls,
            String expectedUpstreamAuthHeader,
            int connectTimeoutMillis,
            int readTimeoutMillis,
            int bufferSize,
            int headerMaxBytes,
            LogLevel logLevel,
            String serverName) {
    }

    /**
     * Log level enumeration ordered by verbosity (ERROR is least verbose).
     * 
     * <p>The ordinal ordering is used for level comparison - a message is logged
     * if its level's ordinal is <= the configured level's ordinal.</p>
     */
    private enum LogLevel {
        ERROR,  // ordinal 0 - only errors
        WARN,   // ordinal 1 - errors + warnings
        INFO,   // ordinal 2 - errors + warnings + info (default)
        DEBUG;  // ordinal 3 - everything

        /** Parses log level from string, defaulting to INFO for invalid values */
        static LogLevel from(String value) {
            if (value == null) {
                return INFO;
            }
            try {
                return LogLevel.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return INFO;
            }
        }
    }

    /**
     * Simple logging utility with level-based filtering.
     * 
     * <p>All output goes to stdout/stderr with timestamp prefix.
     * Uses the RFC 1123 date format for consistency with HTTP headers.</p>
     */
    private static final class Logger {
        private final LogLevel level; // Configured minimum level to log

        Logger(LogLevel level) {
            this.level = level;
        }

        void error(Throwable t, String template, Object... args) {
            log(LogLevel.ERROR, template, args);
            if (t != null) {
                t.printStackTrace(System.err);
            }
        }

        void warn(Throwable t, String template, Object... args) {
            if (isEnabled(LogLevel.WARN)) {
                log(LogLevel.WARN, template, args);
                if (t != null && level == LogLevel.DEBUG) {
                    t.printStackTrace(System.err);
                }
            }
        }

        void warn(String template, Object... args) {
            warn(null, template, args);
        }

        void info(String template, Object... args) {
            if (isEnabled(LogLevel.INFO)) {
                log(LogLevel.INFO, template, args);
            }
        }

        void debug(String template, Object... args) {
            if (isEnabled(LogLevel.DEBUG)) {
                log(LogLevel.DEBUG, template, args);
            }
        }

        private boolean isEnabled(LogLevel requested) {
            return requested.ordinal() <= level.ordinal();
        }

        private void log(LogLevel level, String template, Object... args) {
            String message = args == null || args.length == 0 ? template : template.formatted(args);
            // Format: "Mon, 23 Dec 2024 10:30:00 GMT INFO  message"
            System.out.printf(Locale.ROOT, "%s %-5s %s%n", HttpRequestHead.httpDate(), level, message);
        }
    }
    
    // ==================== HTTP Protocol Records ====================

    /**
     * Represents a single HTTP header (name-value pair).
     * Header name comparison is case-insensitive per HTTP spec.
     */
    private record Header(String name, String value) {
        /** Case-insensitive header name comparison (HTTP spec requirement) */
        boolean nameEquals(String other) {
            return name.equalsIgnoreCase(other);
        }
    }

    /**
     * Represents parsed HTTP request headers (everything before the body).
     * 
     * <p>Structure of an HTTP request:</p>
     * <pre>
     * GET /path HTTP/1.1\r\n          <- startLine
     * Host: example.com\r\n           <- header
     * Content-Length: 100\r\n         <- header
     * \r\n                              <- end of headers
     * [body bytes...]                  <- NOT included in this record
     * </pre>
     */
    private record HttpRequestHead(String startLine, List<Header> headers) {
        /** Extracts HTTP method (GET, POST, CONNECT, etc.) from start line */
        String method() {
            int idx = startLine.indexOf(' ');
            return idx > 0 ? startLine.substring(0, idx) : startLine;
        }

        /** Extracts request target (URL or host:port for CONNECT) from start line */
        String target() {
            String[] parts = startLine.split(" ", 3);
            return parts.length >= 2 ? parts[1] : "";
        }

        /** Finds first header value by name (case-insensitive), or null if not found */
        String firstHeaderValue(String name) {
            for (Header header : headers) {
                if (header.nameEquals(name)) {
                    return header.value();
                }
            }
            return null;
        }

        /**
         * Reads and parses HTTP request headers from input stream.
         * 
         * <p>Reads byte-by-byte until CRLF CRLF sequence (end of headers) or max bytes reached.
         * This approach handles slow clients properly without over-reading into the body.</p>
         * 
         * @param in Input stream to read from
         * @param maxBytes Maximum bytes to read (protection against malicious large headers)
         * @return Parsed request head, or null if malformed/incomplete
         */
        static HttpRequestHead read(InputStream in, int maxBytes) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            // Read byte-by-byte looking for \r\n\r\n (end of headers)
            while (buffer.size() < maxBytes) {
                int b = in.read();
                if (b == -1) {
                    break;
                }
                buffer.write(b);
                if (b == "\r".charAt(0) || b == "\n".charAt(0)) {
                    if (buffer.size() >= 4 && endsWithCrlfCrlf(buffer)) {
                        break;
                    }
                }
            }
            
            // Validate we got complete headers
            byte[] data = buffer.toByteArray();
            if (data.length == 0 || !endsWithCrlfCrlf(buffer)) {
                return null; // Incomplete or empty request
            }
            
            // Parse header text (HTTP headers use ISO-8859-1 encoding)
            String headerText = new String(data, StandardCharsets.ISO_8859_1);
            String[] lines = headerText.split("\r\n");
            if (lines.length == 0) {
                return null;
            }
            
            String startLine = lines[0]; // e.g., "GET /path HTTP/1.1"
            List<Header> headers = new ArrayList<>();
            // Parse each header line (skip first line which is the request line)
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.isEmpty()) {
                    break; // Empty line marks end of headers
                }
                int sep = line.indexOf(':');
                if (sep <= 0) {
                    continue; // Skip malformed header lines
                }
                // Split "Name: Value" and trim whitespace
                String name = line.substring(0, sep).trim();
                String value = line.substring(sep + 1).trim();
                headers.add(new Header(name, value));
            }
            return new HttpRequestHead(startLine, headers);
        }

        /** Checks if buffer ends with CRLF CRLF (\r\n\r\n) - the HTTP header terminator */
        private static boolean endsWithCrlfCrlf(ByteArrayOutputStream buffer) {
            byte[] data = buffer.toByteArray();
            int len = data.length;
            return len >= 4 && data[len - 4] == '\r' && data[len - 3] == '\n' && data[len - 2] == '\r' && data[len - 1] == '\n';
        }

        /** Serializes request line + headers back to HTTP wire format (for forwarding upstream) */
        static byte[] toWire(String startLine, List<Header> headers) {
            StringBuilder builder = new StringBuilder();
            builder.append(startLine).append("\r\n");
            for (Header header : headers) {
                builder.append(header.name()).append(": ").append(header.value()).append("\r\n");
            }
            builder.append("\r\n");
            return builder.toString().getBytes(StandardCharsets.ISO_8859_1);
        }

        /** Returns current UTC time in HTTP date format (RFC 1123) for Date headers */
        static String httpDate() {
            return java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                    .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
        }
    }
    
    // ==================== CLI & Configuration Loading ====================

    /**
     * Command-line interface parser.
     * 
     * <p>Supported argument formats:</p>
     * <ul>
     *   <li>{@code --key=value} - sets option with value</li>
     *   <li>{@code --flag} - sets boolean flag to "true"</li>
     *   <li>{@code --help} or {@code -h} - shows help</li>
     * </ul>
     */
    private record Cli(Map<String, String> options) {
        /** Parses command-line arguments into a map of options */
        static Cli parse(String[] args) {
            Map<String, String> opts = new ConcurrentHashMap<>();
            for (String arg : args) {
                if (arg == null) {
                    continue;
                }
                if (arg.equals("--help") || arg.equals("-h")) {
                    opts.put("help", "true");
                    continue;
                }
                if (!arg.startsWith("--")) {
                    continue;
                }
                String token = arg.substring(2);
                int eq = token.indexOf('=');
                if (eq > 0) {
                    String key = token.substring(0, eq);
                    String value = token.substring(eq + 1);
                    opts.put(key, value);
                } else {
                    opts.put(token, "true");
                }
            }
            return new Cli(opts);
        }

        boolean helpRequested() {
            return Boolean.parseBoolean(options.getOrDefault("help", "false"));
        }

        Optional<String> get(String key) {
            return Optional.ofNullable(options.get(key));
        }

        Set<Map.Entry<String, String>> entries() {
            return options.entrySet();
        }

        static void printHelp() {
            String help = "simple-tunnel options:\n" +
                    "  --config=path               Path to external config properties file\n" +
                    "  --listen.host=HOST          Override listen host\n" +
                    "  --listen.port=PORT          Override listen port\n" +
                    "  --upstream.host=HOST        Upstream HTTPS proxy host\n" +
                    "  --upstream.port=PORT        Upstream proxy port (default 443)\n" +
                    "  --upstream.tls=true|false   Enable TLS when connecting upstream\n" +
                    "  --upstream.username=USER    Username for upstream proxy auth\n" +
                    "  --upstream.password=PASS    Password for upstream proxy auth\n" +
                    "  --listen.username=USER      Require basic auth on local listener\n" +
                    "  --listen.password=PASS      Password for local auth\n" +
                    "  --log.level=LEVEL           ERROR|WARN|INFO|DEBUG\n" +
                    "  --help                      Show this message\n";
            System.out.println(help);
        }
    }

    /**
     * Configuration loader that merges settings from multiple sources.
     * 
     * <p>Loading order (later sources override earlier):</p>
     * <ol>
     *   <li>Classpath resource: /config.properties (defaults)</li>
     *   <li>Working directory: ./config.properties (optional)</li>
     *   <li>CLI specified: --config=path (optional)</li>
     *   <li>CLI arguments: --key=value (highest priority)</li>
     * </ol>
     */
    private static final class ConfigLoader {
        private static final String RESOURCE_CONFIG = "/config.properties";

        private ConfigLoader() {
        }

        /**
         * Loads configuration from all sources and builds the final Config.
         * CLI arguments have the highest priority and override all other sources.
         */
        static Config load(Cli cli) {
            Properties props = new Properties();
            
            // 1. Load defaults from classpath (bundled in JAR)
            loadFromClasspath(props, RESOURCE_CONFIG);

            // 2. Load from working directory (optional override)
            Path workingDirConfig = Path.of("config.properties");
            if (Files.exists(workingDirConfig)) {
                loadFromFile(props, workingDirConfig);
            }

            // 3. Load from CLI-specified config file (optional)
            cli.get("config").map(Path::of).ifPresent(path -> loadFromFile(props, path));

            // 4. Apply CLI arguments (highest priority - overrides everything)
            for (Map.Entry<String, String> entry : cli.entries()) {
                props.setProperty(entry.getKey(), entry.getValue());
            }

            return buildConfig(props);
        }

        /** Builds Config record from Properties, applying defaults for missing values */
        private static Config buildConfig(Properties props) {
            // Local listener settings
            String listenHost = props.getProperty("listen.host", "127.0.0.1");
            int listenPort = parseInt(props, "listen.port", 8282);
            String listenUser = props.getProperty("listen.username", "").trim();
            String listenPass = props.getProperty("listen.password", "").trim();
            // Pre-compute auth header for faster comparison during request handling
            boolean requireClientAuth = !listenUser.isEmpty();
            String expectedClientAuthHeader = requireClientAuth ? basicHeader(listenUser, listenPass) : null;

            // Upstream proxy settings
            String upstreamHost = props.getProperty("upstream.host", "").trim();
            int upstreamPort = parseInt(props, "upstream.port", 443);
            boolean upstreamTls = Boolean.parseBoolean(props.getProperty("upstream.tls", "true"));
            String upstreamUser = props.getProperty("upstream.username", "").trim();
            String upstreamPass = props.getProperty("upstream.password", "").trim();
            String upstreamAuthHeader = upstreamUser.isEmpty() ? null : basicHeader(upstreamUser, upstreamPass);

            // Timeout and buffer settings
            int connectTimeoutMillis = parseInt(props, "timeouts.connectMillis", (int) Duration.ofSeconds(10).toMillis());
            int readTimeoutMillis = parseInt(props, "timeouts.readMillis", (int) Duration.ofSeconds(60).toMillis());
            int bufferSize = parseInt(props, "buffer.size", 16 * 1024);
            int headerLimit = parseInt(props, "header.maxBytes", 32 * 1024);
            LogLevel logLevel = LogLevel.from(props.getProperty("log.level", "INFO"));
            String serverName = props.getProperty("server.name", "simple-tunnel");

            return new Config(
                    listenHost,
                    listenPort,
                    requireClientAuth,
                    expectedClientAuthHeader,
                    upstreamHost,
                    upstreamPort,
                    upstreamTls,
                    upstreamAuthHeader,
                    connectTimeoutMillis,
                    readTimeoutMillis,
                    bufferSize,
                    headerLimit,
                    logLevel,
                    serverName
            );
        }

        private static void loadFromClasspath(Properties props, String resource) {
            try (InputStream in = SimpleProxyTunnel.class.getResourceAsStream(resource)) {
                if (in != null) {
                    props.load(in);
                }
            } catch (IOException ignored) {
                // Classpath config is optional - may not exist
            }
        }

        /** Loads properties from filesystem, with error reporting on failure */
        private static void loadFromFile(Properties props, Path path) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            } catch (IOException ex) {
                System.err.println("Failed to load config from " + path + ": " + ex.getMessage());
            }
        }

        /** Parses integer property with default fallback for missing or invalid values */
        private static int parseInt(Properties props, String key, int defaultValue) {
            String raw = props.getProperty(key);
            if (raw == null) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException ex) {
                return defaultValue; // Invalid number - use default
            }
        }

        /**
         * Creates HTTP Basic authentication header value.
         * Format: "Basic " + Base64(username:password)
         */
        private static String basicHeader(String user, String pass) {
            String credentials = user + ":" + pass;
            String token = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            return "Basic " + token;
        }
    }
}