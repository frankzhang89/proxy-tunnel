package xzy.fz.logging;

import xzy.fz.config.TunnelConfig;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SquidLogger {
    private static final DateTimeFormatter SQUID_TIME = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.US);

    private final PrintWriter writer;
    private final AtomicBoolean closed = new AtomicBoolean();

    private SquidLogger(PrintWriter writer) {
        this.writer = writer;
    }

    public static SquidLogger create(TunnelConfig config) {
        String logPath = config.logFile();
        if (logPath == null || logPath.isBlank()) {
            return new SquidLogger(new PrintWriter(System.out, true));
        }
        try {
            Path path = Path.of(logPath);
            Files.createDirectories(path.getParent());
            PrintWriter writer = new PrintWriter(Files.newBufferedWriter(path,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND), true);
            return new SquidLogger(writer);
        } catch (IOException ioe) {
            throw new IllegalStateException("Unable to open squid log file", ioe);
        }
    }

    public void log(String clientIp, String method, String uri, int status, long bytes) {
        String now = ZonedDateTime.now().format(SQUID_TIME);
        writer.printf(Locale.US, "%s %s %s %s %d %d%n", now, clientIp, method, uri, status, bytes);
    }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            writer.flush();
            writer.close();
        }
    }
}

