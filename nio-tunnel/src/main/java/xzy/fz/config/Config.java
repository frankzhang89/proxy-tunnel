package xzy.fz.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Immutable configuration record for nio-tunnel.
 * <p>
 * All settings can be configured via properties file or CLI arguments.
 * Configuration is loaded in priority order:
 * <ol>
 *   <li>Classpath resource /config.properties (lowest priority)</li>
 *   <li>Working directory config.properties</li>
 *   <li>CLI-specified config file (--config=path)</li>
 *   <li>Individual CLI overrides (--key=value) (highest priority)</li>
 * </ol>
 *
 * @param listenHost               Local bind address for proxy listeners
 * @param listenPort               HTTP proxy listen port
 * @param socksPort                SOCKS5 proxy listen port
 * @param requireClientAuth        Whether to require client authentication
 * @param expectedClientAuthHeader Expected Basic auth header for client auth
 * @param upstreamHost             Upstream HTTPS proxy hostname
 * @param upstreamPort             Upstream HTTPS proxy port
 * @param upstreamTls              Whether to use TLS for upstream connection
 * @param expectedUpstreamAuthHeader Basic auth header for upstream proxy
 * @param connectTimeoutMillis     Connection timeout for upstream proxy
 * @param httpMaxInitialBytes      Maximum size for HTTP headers
 * @param pacEnabled               Whether PAC file serving is enabled
 * @param pacPath                  URL path for PAC file
 * @param pacHost                  Host to use in generated PAC file
 * @param pacFile                  Optional path to custom PAC file
 * @param serverName               Server name for HTTP headers
 * @param logFile                  Optional log file path (deprecated, use accessLogFile)
 * @param accessLogFile            Access log file path (Squid-style format)
 * @param accessLogConsole         Whether to output access log to console
 * @param accessLogEnabled         Whether access logging is enabled
 */
public record Config(
        String listenHost,
        int listenPort,
        int socksPort,
        boolean requireClientAuth,
        String expectedClientAuthHeader,
        String upstreamHost,
        int upstreamPort,
        boolean upstreamTls,
        String expectedUpstreamAuthHeader,
        int connectTimeoutMillis,
        int httpMaxInitialBytes,
        boolean pacEnabled,
        String pacPath,
        String pacHost,
        String pacFile,
        String serverName,
        String logFile,
        String accessLogFile,
        boolean accessLogConsole,
        boolean accessLogEnabled
) {
    private static final Logger log = LoggerFactory.getLogger(Config.class);

    /**
     * Generates PAC (Proxy Auto-Config) file content.
     * <p>
     * If a custom PAC file is specified via {@link #pacFile}, reads from that file.
     * Otherwise, generates a default PAC that:
     * <ul>
     *   <li>Bypasses proxy for localhost and private network addresses</li>
     *   <li>Routes all other traffic through this proxy (SOCKS5 preferred, HTTP fallback)</li>
     * </ul>
     *
     * @return PAC file content as JavaScript
     */
    public String pacContent() {
        // Try to read custom PAC file if specified
        if (pacFile != null && !pacFile.isBlank()) {
            try {
                return Files.readString(Path.of(pacFile));
            } catch (IOException e) {
                log.warn("Failed to read PAC file {}: {}", pacFile, e.getMessage());
            }
        }

        // Default PAC file - proxy all traffic through this proxy
        return """
            function FindProxyForURL(url, host) {
                // Bypass proxy for localhost and private networks
                if (isPlainHostName(host) ||
                    shExpMatch(host, "localhost") ||
                    shExpMatch(host, "127.*") ||
                    shExpMatch(host, "10.*") ||
                    shExpMatch(host, "172.16.*") ||
                    shExpMatch(host, "192.168.*")) {
                    return "DIRECT";
                }
                // Use SOCKS5 proxy for everything else, with HTTP proxy as fallback
                return "SOCKS5 %s:%d; PROXY %s:%d; DIRECT";
            }
            """.formatted(pacHost, socksPort, pacHost, listenPort);
    }
}
