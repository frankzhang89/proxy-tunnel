package xzy.fz.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;

/**
 * Loads configuration from multiple sources with layered overrides.
 * <p>
 * Configuration sources are loaded in the following order (later sources override earlier):
 * <ol>
 *   <li>Classpath resource /config.properties (bundled defaults)</li>
 *   <li>config.properties in working directory</li>
 *   <li>Config file specified via CLI --config=path</li>
 *   <li>Individual CLI argument overrides</li>
 * </ol>
 */
public final class ConfigLoader {
    /** Path to default configuration resource in classpath */
    private static final String RESOURCE_CONFIG = "/config.properties";

    private ConfigLoader() {
        // Utility class - prevent instantiation
    }

    /**
     * Loads configuration from all sources and builds a Config instance.
     *
     * @param cli Parsed command-line arguments
     * @return Fully resolved configuration
     */
    public static Config load(Cli cli) {
        Properties props = new Properties();

        // 1. Load from classpath (default config bundled in JAR)
        loadFromClasspath(props, RESOURCE_CONFIG);

        // 2. Load from working directory if exists
        Path workingDirConfig = Path.of("config.properties");
        if (Files.exists(workingDirConfig)) {
            loadFromFile(props, workingDirConfig);
        }

        // 3. Load from CLI-specified config file
        cli.get("config").map(Path::of).ifPresent(path -> loadFromFile(props, path));

        // 4. Apply CLI overrides (highest priority)
        for (Map.Entry<String, String> entry : cli.entries()) {
            props.setProperty(entry.getKey(), entry.getValue());
        }

        return buildConfig(props);
    }

    /**
     * Builds a Config instance from resolved properties.
     */
    private static Config buildConfig(Properties props) {
        // Listener settings
        String listenHost = props.getProperty("listen.host", "127.0.0.1");
        int listenPort = parseInt(props, "listen.port", 8383);
        int socksPort = parseInt(props, "listen.socks.port", 1080);

        // Client authentication
        String listenUser = props.getProperty("listen.username", "").trim();
        String listenPass = props.getProperty("listen.password", "").trim();
        boolean requireClientAuth = !listenUser.isEmpty();
        String expectedClientAuthHeader = requireClientAuth ? basicHeader(listenUser, listenPass) : null;

        // Upstream proxy settings
        String upstreamHost = props.getProperty("upstream.host", "").trim();
        int upstreamPort = parseInt(props, "upstream.port", 443);
        boolean upstreamTls = Boolean.parseBoolean(props.getProperty("upstream.tls", "true"));
        String upstreamUser = props.getProperty("upstream.username", "").trim();
        String upstreamPass = props.getProperty("upstream.password", "").trim();
        String upstreamAuthHeader = upstreamUser.isEmpty() ? null : basicHeader(upstreamUser, upstreamPass);

        // Connection settings
        int connectTimeoutMillis = parseInt(props, "connect.timeout.millis", 5000);
        int httpMaxInitialBytes = parseInt(props, "http.maxInitialBytes", 1048576);

        // PAC settings
        boolean pacEnabled = Boolean.parseBoolean(props.getProperty("pac.enabled", "true"));
        String pacPath = props.getProperty("pac.path", "/proxy.pac");
        String pacHost = props.getProperty("pac.host", "127.0.0.1");
        String pacFile = props.getProperty("pac.file");

        // Misc settings
        String serverName = props.getProperty("server.name", "nio-tunnel");
        String logFile = props.getProperty("log.file");

        // Access log settings (Squid-style)
        boolean accessLogEnabled = Boolean.parseBoolean(props.getProperty("access.log.enabled", "true"));
        String accessLogFile = props.getProperty("access.log.file");
        boolean accessLogConsole = Boolean.parseBoolean(props.getProperty("access.log.console", "true"));

        return new Config(
                listenHost, listenPort, socksPort,
                requireClientAuth, expectedClientAuthHeader,
                upstreamHost, upstreamPort, upstreamTls, upstreamAuthHeader,
                connectTimeoutMillis, httpMaxInitialBytes,
                pacEnabled, pacPath, pacHost, pacFile,
                serverName, logFile,
                accessLogFile, accessLogConsole, accessLogEnabled
        );
    }

    /**
     * Loads properties from a classpath resource.
     */
    private static void loadFromClasspath(Properties props, String resource) {
        try (InputStream in = ConfigLoader.class.getResourceAsStream(resource)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // Classpath resource not found - continue with defaults
        }
    }

    /**
     * Loads properties from a file path.
     */
    private static void loadFromFile(Properties props, Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException ex) {
            System.err.println("Failed to load config from " + path + ": " + ex.getMessage());
        }
    }

    /**
     * Parses an integer property with a default value.
     */
    private static int parseInt(Properties props, String key, int defaultValue) {
        String raw = props.getProperty(key);
        if (raw == null) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    /**
     * Creates a Basic authentication header value.
     *
     * @param user Username
     * @param pass Password
     * @return "Basic base64(user:pass)"
     */
    public static String basicHeader(String user, String pass) {
        String credentials = user + ":" + pass;
        String token = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }
}
