package xzy.fz.logging;

public record SquidLogEntry(
        String clientIp,
        String method,
        String uri,
        int status,
        long bytes
) {
}

