package xzy.fz.config;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal CLI parser that accepts flag syntax like --key=value.
 */
public final class CliArguments {
    private final Map<String, String> options;
    private final boolean help;

    private CliArguments(Map<String, String> options, boolean help) {
        this.options = options;
        this.help = help;
    }

    public static CliArguments parse(String[] args) {
        Map<String, String> opts = new ConcurrentHashMap<>();
        boolean help = false;
        if (args != null) {
            for (String arg : args) {
                if (arg == null) {
                    continue;
                }
                String trimmed = arg.trim();
                if (trimmed.equals("--help") || trimmed.equals("-h")) {
                    help = true;
                    continue;
                }
                if (!trimmed.startsWith("--") || !trimmed.contains("=")) {
                    continue;
                }
                int idx = trimmed.indexOf('=');
                String key = trimmed.substring(2, idx).trim().toLowerCase(Locale.ROOT);
                String value = trimmed.substring(idx + 1).trim();
                if (!key.isEmpty()) {
                    opts.put(key, value);
                }
            }
        }
        return new CliArguments(Collections.unmodifiableMap(opts), help);
    }

    public static void printHelp() {
        System.out.println("Usage: java -jar nio-tunnel.jar --upstream.host=proxy.example --config=/path/to/config.properties");
        System.out.println("Flags accept the same keys as config.properties (use --key=value syntax).");
    }

    public boolean helpRequested() {
        return help;
    }

    public Map<String, String> overrides() {
        return options;
    }

    public Path customConfigPath() {
        String value = options.get("config");
        return value == null || value.isBlank() ? null : Path.of(value);
    }
}

