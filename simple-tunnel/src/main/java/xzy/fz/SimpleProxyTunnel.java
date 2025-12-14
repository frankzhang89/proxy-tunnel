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

public final class SimpleProxyTunnel {
    private SimpleProxyTunnel() {
    }

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
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "simple-tunnel-shutdown"));
        server.start();
    }

    private static final class TunnelServer {
        private final Config config;
        private final Logger log;
        private final AtomicBoolean running = new AtomicBoolean();
        private volatile ServerSocket serverSocket;

        TunnelServer(Config config, Logger log) {
            this.config = config;
            this.log = log;
        }

        void start() {
            if (!running.compareAndSet(false, true)) {
                return;
            }

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                ServerSocket localSocket = new ServerSocket();
                this.serverSocket = localSocket;
                try (localSocket) {
                    InetAddress bindAddress = InetAddress.getByName(config.listenHost());
                    localSocket.bind(new InetSocketAddress(bindAddress, config.listenPort()));
                    log.info("Listening on %s:%d", bindAddress.getHostAddress(), config.listenPort());

                    while (running.get()) {
                        Socket client = localSocket.accept();
                        executor.submit(() -> handleClient(client));
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

        void stop() {
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

        private void handleClient(Socket client) {
            String clientId = client.getRemoteSocketAddress().toString();
            log.debug("Accepted connection %s", clientId);

            try (client) {
                client.setSoTimeout(config.readTimeoutMillis());
                BufferedInputStream clientIn = new BufferedInputStream(client.getInputStream());
                BufferedOutputStream clientOut = new BufferedOutputStream(client.getOutputStream());

                HttpRequestHead request = HttpRequestHead.read(clientIn, config.headerMaxBytes());
                if (request == null) {
                    sendError(clientOut, "HTTP/1.1 400 Bad Request", "Malformed request", config.serverName());
                    return;
                }

                if (config.requireClientAuth()) {
                    String supplied = request.firstHeaderValue("Proxy-Authorization");
                    if (supplied == null || !supplied.equals(config.expectedClientAuthHeader())) {
                        log.warn("Rejected unauthenticated request from %s", clientId);
                        sendProxyAuthRequired(clientOut, config.serverName());
                        return;
                    }
                }

                log.info("%s %s via %s", request.method(), request.target(), clientId);

                try (Socket upstream = openUpstreamSocket()) {
                    upstream.setSoTimeout(config.readTimeoutMillis());
                    BufferedInputStream upstreamIn = new BufferedInputStream(upstream.getInputStream());
                    BufferedOutputStream upstreamOut = new BufferedOutputStream(upstream.getOutputStream());

                    List<Header> forwardedHeaders = filterAndAugmentHeaders(request.headers(), config);
                    byte[] upstreamHead = HttpRequestHead.toWire(request.startLine(), forwardedHeaders);
                    upstreamOut.write(upstreamHead);
                    upstreamOut.flush();

                    bridge(clientIn, clientOut, upstreamIn, upstreamOut);
                }
            } catch (IOException ioe) {
                log.warn(ioe, "Connection error: %s", ioe.getMessage());
            }
        }

        private Socket openUpstreamSocket() throws IOException {
            SocketFactory factory = config.upstreamTls() ? SSLSocketFactory.getDefault() : SocketFactory.getDefault();
            Socket upstream = factory.createSocket();
            upstream.connect(new InetSocketAddress(config.upstreamHost(), config.upstreamPort()), config.connectTimeoutMillis());
            return upstream;
        }

        private void bridge(InputStream clientIn,
                             OutputStream clientOut,
                             InputStream upstreamIn,
                             OutputStream upstreamOut) {
            Thread up = Thread.startVirtualThread(() -> pump(clientIn, upstreamOut));
            Thread down = Thread.startVirtualThread(() -> pump(upstreamIn, clientOut));
            try {
                up.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                down.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        private long pump(InputStream in, OutputStream out) {
            byte[] buffer = new byte[config.bufferSize()];
            long total = 0L;
            try {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    out.flush();
                    total += read;
                }
            } catch (IOException ignored) {
                // connection tear-down is expected; logging would be too noisy here
            }
            return total;
        }

        private List<Header> filterAndAugmentHeaders(List<Header> headers, Config cfg) {
            List<Header> filtered = new ArrayList<>();
            for (Header header : headers) {
                if (header.nameEquals("Proxy-Authorization")) {
                    continue; // never forward client credentials upstream
                }
                filtered.add(header);
            }
            if (cfg.expectedUpstreamAuthHeader() != null) {
                filtered.add(new Header("Proxy-Authorization", cfg.expectedUpstreamAuthHeader()));
            }
            filtered.add(new Header("Proxy-Connection", "keep-alive"));
            return filtered;
        }

        private void sendError(OutputStream clientOut, String statusLine, String body, String serverName) throws IOException {
            String payload = "<html><body><h1>" + body + "</h1></body></html>";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            String response = statusLine + "\r\n" +
                    "Date: " + HttpRequestHead.httpDate() + "\r\n" +
                    "Server: " + serverName + "\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: " + bytes.length + "\r\n" +
                    "Connection: close\r\n\r\n";
            clientOut.write(response.getBytes(StandardCharsets.ISO_8859_1));
            clientOut.write(bytes);
            clientOut.flush();
        }

        private void sendProxyAuthRequired(OutputStream clientOut, String serverName) throws IOException {
            String response = "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                    "Date: " + HttpRequestHead.httpDate() + "\r\n" +
                    "Proxy-Authenticate: Basic realm=\"" + serverName + "\"\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n";
            clientOut.write(response.getBytes(StandardCharsets.ISO_8859_1));
            clientOut.flush();
        }
    }

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

    private enum LogLevel {
        ERROR,
        WARN,
        INFO,
        DEBUG;

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

    private static final class Logger {
        private final LogLevel level;

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
            System.out.printf(Locale.ROOT, "%s %-5s %s%n", HttpRequestHead.httpDate(), level, message);
        }
    }

    private record Header(String name, String value) {
        boolean nameEquals(String other) {
            return name.equalsIgnoreCase(other);
        }
    }

    private record HttpRequestHead(String startLine, List<Header> headers) {
        String method() {
            int idx = startLine.indexOf(' ');
            return idx > 0 ? startLine.substring(0, idx) : startLine;
        }

        String target() {
            String[] parts = startLine.split(" ", 3);
            return parts.length >= 2 ? parts[1] : "";
        }

        String firstHeaderValue(String name) {
            for (Header header : headers) {
                if (header.nameEquals(name)) {
                    return header.value();
                }
            }
            return null;
        }

        static HttpRequestHead read(InputStream in, int maxBytes) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
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
            byte[] data = buffer.toByteArray();
            if (data.length == 0 || !endsWithCrlfCrlf(buffer)) {
                return null;
            }
            String headerText = new String(data, StandardCharsets.ISO_8859_1);
            String[] lines = headerText.split("\r\n");
            if (lines.length == 0) {
                return null;
            }
            String startLine = lines[0];
            List<Header> headers = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.isEmpty()) {
                    break;
                }
                int sep = line.indexOf(':');
                if (sep <= 0) {
                    continue;
                }
                String name = line.substring(0, sep).trim();
                String value = line.substring(sep + 1).trim();
                headers.add(new Header(name, value));
            }
            return new HttpRequestHead(startLine, headers);
        }

        private static boolean endsWithCrlfCrlf(ByteArrayOutputStream buffer) {
            byte[] data = buffer.toByteArray();
            int len = data.length;
            return len >= 4 && data[len - 4] == '\r' && data[len - 3] == '\n' && data[len - 2] == '\r' && data[len - 1] == '\n';
        }

        static byte[] toWire(String startLine, List<Header> headers) {
            StringBuilder builder = new StringBuilder();
            builder.append(startLine).append("\r\n");
            for (Header header : headers) {
                builder.append(header.name()).append(": ").append(header.value()).append("\r\n");
            }
            builder.append("\r\n");
            return builder.toString().getBytes(StandardCharsets.ISO_8859_1);
        }

        static String httpDate() {
            return java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                    .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
        }
    }

    private record Cli(Map<String, String> options) {
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

    private static final class ConfigLoader {
        private static final String RESOURCE_CONFIG = "/config.properties";

        private ConfigLoader() {
        }

        static Config load(Cli cli) {
            Properties props = new Properties();
            loadFromClasspath(props, RESOURCE_CONFIG);

            Path workingDirConfig = Path.of("config.properties");
            if (Files.exists(workingDirConfig)) {
                loadFromFile(props, workingDirConfig);
            }

            cli.get("config").map(Path::of).ifPresent(path -> loadFromFile(props, path));

            for (Map.Entry<String, String> entry : cli.entries()) {
                props.setProperty(entry.getKey(), entry.getValue());
            }

            return buildConfig(props);
        }

        private static Config buildConfig(Properties props) {
            String listenHost = props.getProperty("listen.host", "127.0.0.1");
            int listenPort = parseInt(props, "listen.port", 8282);
            String listenUser = props.getProperty("listen.username", "").trim();
            String listenPass = props.getProperty("listen.password", "").trim();
            boolean requireClientAuth = !listenUser.isEmpty();
            String expectedClientAuthHeader = requireClientAuth ? basicHeader(listenUser, listenPass) : null;

            String upstreamHost = props.getProperty("upstream.host", "").trim();
            int upstreamPort = parseInt(props, "upstream.port", 443);
            boolean upstreamTls = Boolean.parseBoolean(props.getProperty("upstream.tls", "true"));
            String upstreamUser = props.getProperty("upstream.username", "").trim();
            String upstreamPass = props.getProperty("upstream.password", "").trim();
            String upstreamAuthHeader = upstreamUser.isEmpty() ? null : basicHeader(upstreamUser, upstreamPass);

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
            }
        }

        private static void loadFromFile(Properties props, Path path) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            } catch (IOException ex) {
                System.err.println("Failed to load config from " + path + ": " + ex.getMessage());
            }
        }

        private static int parseInt(Properties props, String key, int defaultValue) {
            String raw = props.getProperty(key);
            if (raw == null) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException ex) {
                return defaultValue;
            }
        }

        private static String basicHeader(String user, String pass) {
            String credentials = user + ":" + pass;
            String token = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            return "Basic " + token;
        }
    }
}