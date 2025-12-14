package xzy.fz.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

public final class ConfigLoader {
    private ConfigLoader() {
    }

    public static TunnelConfig load(CliArguments cli) {
        Properties props = new Properties();
        try (InputStream in = ConfigLoader.class.getResourceAsStream("/config.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ioe) {
            throw new IllegalStateException("Unable to read default config.properties", ioe);
        }

        Path extraConfig = cli.customConfigPath();
        if (extraConfig != null && Files.exists(extraConfig)) {
            try (InputStream in = Files.newInputStream(extraConfig)) {
                props.load(in);
            } catch (IOException ioe) {
                throw new IllegalStateException("Unable to read custom config " + extraConfig, ioe);
            }
        }

        for (Map.Entry<String, String> entry : cli.overrides().entrySet()) {
            props.setProperty(entry.getKey(), entry.getValue());
        }

        return new TunnelConfig(
                props.getProperty("listen.host", "127.0.0.1"),
                intProp(props, "listen.port", 8383),
                intProp(props, "listen.socks.port", 0),
                props.getProperty("upstream.host", ""),
                intProp(props, "upstream.port", 443),
                boolProp(props, "upstream.tls", true),
                props.getProperty("upstream.username"),
                props.getProperty("upstream.password"),
                props.getProperty("listen.username"),
                props.getProperty("listen.password"),
                Duration.ofMillis(longProp(props, "connect.timeout.millis", 5000L)),
                intProp(props, "http.maxInitialBytes", 1_048_576),
                boolProp(props, "pac.enabled", true),
                props.getProperty("pac.host", "127.0.0.1"),
                intProp(props, "pac.port", intProp(props, "listen.port", 8383)),
                props.getProperty("pac.path", "/proxy.pac"),
                props.getProperty("pac.file"),
                props.getProperty("server.name", "nio-tunnel"),
                props.getProperty("log.file"));
    }

    private static int intProp(Properties props, String key, int defaultValue) {
        String value = props.getProperty(key);
        return value == null ? defaultValue : Integer.parseInt(value.trim());
    }

    private static long longProp(Properties props, String key, long defaultValue) {
        String value = props.getProperty(key);
        return value == null ? defaultValue : Long.parseLong(value.trim());
    }

    private static boolean boolProp(Properties props, String key, boolean defaultValue) {
        String value = props.getProperty(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value.trim());
    }
}
