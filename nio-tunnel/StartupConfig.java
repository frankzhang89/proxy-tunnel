import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Source-mode helper shared by the launch scripts; never starts the server. */
class StartupConfig {
    public static void main(String[] args) {
        try {
            Path jar = Path.of(args[0]).toRealPath();
            String[] options = Arrays.copyOfRange(args, 1, args.length);
            // Reuse the application's loader instead of duplicating Java properties parsing.
            try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
                Class<?> cliClass = loader.loadClass("xzy.fz.config.Cli");
                Object cli = cliClass.getMethod("parse", String[].class).invoke(null, (Object) options);
                @SuppressWarnings("unchecked")
                var values = (java.util.Map<String, String>) cliClass.getMethod("options").invoke(cli);
                if (values.containsKey("config") && !Files.isRegularFile(Path.of(values.get("config")))) {
                    throw new IllegalArgumentException("Config file does not exist: " + values.get("config"));
                }
                Object config = loader.loadClass("xzy.fz.config.ConfigLoader")
                        .getMethod("load", cliClass).invoke(null, cli);
                String host = (String) get(config, "listenHost");
                String upstream = (String) get(config, "upstreamHost");
                int httpPort = (int) get(config, "listenPort");
                int socksPort = (int) get(config, "socksPort");
                // Reject ephemeral or duplicate ports: the launcher must verify both listeners.
                if (httpPort < 1 || httpPort > 65535 || socksPort < 1 || socksPort > 65535
                        || httpPort == socksPort) {
                    throw new IllegalArgumentException("HTTP and SOCKS ports must be distinct and within 1..65535.");
                }
                if (upstream.isBlank()) throw new IllegalArgumentException("Missing required property upstream.host.");
                print("LISTEN_HOST", host);
                print("BIND_ADDRESS", canonicalAddress(InetAddress.getByName(host)));
                print("HTTP_PORT", httpPort);
                print("SOCKS_PORT", socksPort);
                print("UPSTREAM_HOST", upstream);
                print("UPSTREAM_PORT", get(config, "upstreamPort"));
                String accessLog = (String) get(config, "accessLogFile");
                print("ACCESS_LOG", (boolean) get(config, "accessLogEnabled") && accessLog != null
                        && !accessLog.isBlank() ? Path.of(accessLog).toAbsolutePath().normalize() : "Disabled");
            }
        } catch (Exception ex) {
            Throwable cause = ex instanceof InvocationTargetException ? ex.getCause() : ex;
            System.err.println("Configuration check failed: " + cause.getMessage());
            System.exit(1);
        }
    }

    private static Object get(Object config, String name) throws Exception {
        return config.getClass().getMethod(name).invoke(config);
    }

    private static String canonicalAddress(InetAddress address) {
        String text = address.getHostAddress();
        if (!text.contains(":")) return text;
        // Match the compressed IPv6 spelling used by Linux ss / inet_ntop.
        int scope = text.indexOf('%');
        String suffix = scope < 0 ? "" : text.substring(scope);
        String[] groups = (scope < 0 ? text : text.substring(0, scope)).split(":");
        int bestStart = -1, bestLength = 1;
        for (int i = 0; i < groups.length;) {
            if (!groups[i].equals("0")) { i++; continue; }
            int start = i;
            while (i < groups.length && groups[i].equals("0")) i++;
            if (i - start > bestLength) { bestStart = start; bestLength = i - start; }
        }
        var result = new StringBuilder();
        for (int i = 0; i < groups.length;) {
            if (i == bestStart) { result.append("::"); i += bestLength; }
            else {
                if (!result.isEmpty() && result.charAt(result.length() - 1) != ':') result.append(':');
                result.append(groups[i++]);
            }
        }
        return result + suffix;
    }

    private static void print(String key, Object value) {
        String text = value.toString();
        if (text.contains("\n") || text.contains("\r")) {
            throw new IllegalArgumentException("Configuration values must not contain line breaks: " + key);
        }
        System.out.println(key + "=" + text);
    }
}
