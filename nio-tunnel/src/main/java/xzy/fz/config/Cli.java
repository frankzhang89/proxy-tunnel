package xzy.fz.config;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple command-line argument parser for nio-tunnel.
 * <p>
 * Supports the following argument formats:
 * <ul>
 *   <li>{@code --key=value} - Set a configuration property</li>
 *   <li>{@code --flag} - Set a boolean flag to true</li>
 *   <li>{@code --help} or {@code -h} - Request help message</li>
 * </ul>
 *
 * @param options Parsed command-line options as key-value pairs
 */
public record Cli(Map<String, String> options) {

    /**
     * Parses command-line arguments into a Cli instance.
     *
     * @param args Command-line arguments from main()
     * @return Parsed Cli instance
     */
    public static Cli parse(String[] args) {
        Map<String, String> opts = new ConcurrentHashMap<>();

        for (String arg : args) {
            if (arg == null) continue;

            // Handle help flags
            if (arg.equals("--help") || arg.equals("-h")) {
                opts.put("help", "true");
                continue;
            }

            // Skip non-option arguments
            if (!arg.startsWith("--")) continue;

            // Parse --key=value or --flag
            String token = arg.substring(2);
            int eq = token.indexOf('=');
            if (eq > 0) {
                opts.put(token.substring(0, eq), token.substring(eq + 1));
            } else {
                opts.put(token, "true");
            }
        }

        return new Cli(opts);
    }

    /**
     * Checks if help was requested via --help or -h.
     *
     * @return true if help was requested
     */
    public boolean helpRequested() {
        return Boolean.parseBoolean(options.getOrDefault("help", "false"));
    }

    /**
     * Gets an optional configuration value by key.
     *
     * @param key The configuration key
     * @return Optional containing the value if present
     */
    public Optional<String> get(String key) {
        return Optional.ofNullable(options.get(key));
    }

    /**
     * Gets all configuration entries for iteration.
     *
     * @return Set of all key-value entries
     */
    public Set<Map.Entry<String, String>> entries() {
        return options.entrySet();
    }

    /**
     * Prints the help message to stdout.
     */
    public static void printHelp() {
        String help = """
            nio-tunnel - NIO-based HTTP/SOCKS proxy with upstream HTTPS forwarding
            
            This tool provides a local HTTP and SOCKS5 proxy that forwards all traffic
            to an upstream HTTPS proxy. It's designed for environments where direct
            HTTPS proxy configuration is not supported (e.g., JetBrains IDEs).
            
            Options:
              --config=PATH               Path to external config properties file
              --listen.host=HOST          Listen address (default: 127.0.0.1)
              --listen.port=PORT          HTTP proxy port (default: 8383)
              --listen.socks.port=PORT    SOCKS5 proxy port (default: 1080)
              --upstream.host=HOST        Upstream HTTPS proxy host (required)
              --upstream.port=PORT        Upstream proxy port (default: 443)
              --upstream.tls=BOOL         Enable TLS for upstream (default: true)
              --upstream.username=USER    Upstream proxy username
              --upstream.password=PASS    Upstream proxy password
              --listen.username=USER      Require local auth (username)
              --listen.password=PASS      Local auth password
              --pac.enabled=BOOL          Enable PAC file serving (default: true)
              --pac.path=PATH             PAC file URL path (default: /proxy.pac)
              --pac.host=HOST             Host in PAC file (default: 127.0.0.1)
              --pac.file=FILE             Custom PAC file path
              --server.name=NAME          Server name for headers
              --help, -h                  Show this help
            
            Example:
              java -jar nio-tunnel.jar --upstream.host=proxy.example.com --upstream.port=443
            
            Configuration Priority (highest to lowest):
              1. CLI arguments (--key=value)
              2. Config file specified via --config=path
              3. config.properties in working directory
              4. config.properties in JAR (defaults)
            """;
        System.out.println(help);
    }
}
