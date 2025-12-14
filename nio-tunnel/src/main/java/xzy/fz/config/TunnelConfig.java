package xzy.fz.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

public record TunnelConfig(
        String listenHost,
        int listenPort,
        int socksPort,
        String upstreamHost,
        int upstreamPort,
        boolean upstreamTls,
        String upstreamUsername,
        String upstreamPassword,
        String listenUsername,
        String listenPassword,
        Duration connectTimeout,
        int httpMaxInitialBytes,
        boolean pacEnabled,
        String pacHost,
        int pacPort,
        String pacPath,
        String pacFile,
        String serverName,
        String logFile
) {
    public boolean requireClientAuth() {
        return listenUsername != null && !listenUsername.isBlank();
    }

    public boolean upstreamAuthEnabled() {
        return upstreamUsername != null && !upstreamUsername.isBlank();
    }

    public boolean socksEnabled() {
        return socksPort > 0;
    }

    public String expectedClientAuthHeader() {
        if (!requireClientAuth()) {
            return null;
        }
        String password = listenPassword == null ? "" : listenPassword;
        return basicHeader(listenUsername, password);
    }

    public String upstreamAuthHeader() {
        if (!upstreamAuthEnabled()) {
            return null;
        }
        String password = upstreamPassword == null ? "" : upstreamPassword;
        return basicHeader(upstreamUsername, password);
    }

    private static String basicHeader(String user, String pass) {
        String raw = user + ":" + pass;
        String token = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.ISO_8859_1));
        return "Basic " + token;
    }
}
