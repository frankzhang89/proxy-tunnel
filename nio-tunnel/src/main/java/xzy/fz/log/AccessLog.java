package xzy.fz.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Squid-style access log writer with high-readability timestamps.
 * <p>
 * Log format (similar to Squid):
 * <pre>
 * timestamp duration client action/code size method URL user hierarchy/from content-type
 * </pre>
 * <p>
 * Example output:
 * <pre>
 * 2025-12-31 10:30:45 150 192.168.1.100 TCP_TUNNEL/200 1234 CONNECT example.com:443 - HIER_DIRECT/example.com -
 * 2025-12-31 10:30:46 200 192.168.1.100 TCP_MISS/200 5678 GET http://example.com/ - HIER_DIRECT/example.com text/html
 * </pre>
 * <p>
 * The log writer uses an async queue to avoid blocking request handling.
 */
public final class AccessLog implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AccessLog.class);

    /** Timestamp format: yyyy-MM-dd HH:mm:ss for high readability */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Async log queue to avoid blocking request handling */
    private final BlockingQueue<String> logQueue = new LinkedBlockingQueue<>(10000);

    /** Writer thread running flag */
    private final AtomicBoolean running = new AtomicBoolean(true);

    /** Log file path (null for stdout only) */
    private final Path logFilePath;

    /** Log file writer */
    private PrintWriter fileWriter;

    /** Writer thread */
    private final Thread writerThread;

    /** Whether to also output to console */
    private final boolean consoleOutput;

    /**
     * Creates an access log instance.
     *
     * @param logFile       Path to log file (null or empty for stdout only)
     * @param consoleOutput Whether to also output to console
     */
    public AccessLog(String logFile, boolean consoleOutput) {
        this.consoleOutput = consoleOutput;

        if (logFile != null && !logFile.isBlank()) {
            this.logFilePath = Path.of(logFile);
            initFileWriter();
        } else {
            this.logFilePath = null;
        }

        // Start async writer thread
        this.writerThread = new Thread(this::writeLoop, "access-log-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();

        log.info("Access log initialized: file={}, console={}",
                logFilePath != null ? logFilePath : "disabled", consoleOutput);
    }

    /**
     * Initializes the file writer.
     */
    private void initFileWriter() {
        try {
            // Create parent directories if needed
            if (logFilePath.getParent() != null) {
                Files.createDirectories(logFilePath.getParent());
            }

            // Open file for appending
            OutputStream out = Files.newOutputStream(logFilePath,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            this.fileWriter = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(out, StandardCharsets.UTF_8)), true);

            log.debug("Access log file opened: {}", logFilePath);
        } catch (IOException e) {
            log.error("Failed to open access log file {}: {}", logFilePath, e.getMessage());
            this.fileWriter = null;
        }
    }

    /**
     * Logs a completed request in Squid-style format.
     *
     * @param entry The access log entry to write
     */
    public void log(AccessLogEntry entry) {
        String line = formatEntry(entry);
        if (!logQueue.offer(line)) {
            log.warn("Access log queue full, dropping entry");
        }
    }

    /**
     * Logs a CONNECT tunnel request.
     *
     * @param clientAddress Client IP address
     * @param target        Target host:port
     * @param statusCode    HTTP status code
     * @param durationMs    Request duration in milliseconds
     * @param bytesWritten  Total bytes transferred
     */
    public void logConnect(String clientAddress, String target, int statusCode,
                           long durationMs, long bytesWritten) {
        AccessLogEntry entry = new AccessLogEntry(
                LocalDateTime.now(),
                durationMs,
                clientAddress,
                "TCP_TUNNEL",
                statusCode,
                bytesWritten,
                "CONNECT",
                target,
                "-",
                "HIER_DIRECT/" + extractHost(target),
                "-"
        );
        log(entry);
    }

    /**
     * Logs a SOCKS5 CONNECT request.
     *
     * @param clientAddress Client IP address
     * @param target        Target host:port
     * @param success       Whether connection was successful
     * @param durationMs    Request duration in milliseconds
     * @param bytesWritten  Total bytes transferred
     */
    public void logSocks5Connect(String clientAddress, String target, boolean success,
                                  long durationMs, long bytesWritten) {
        AccessLogEntry entry = new AccessLogEntry(
                LocalDateTime.now(),
                durationMs,
                clientAddress,
                success ? "TCP_TUNNEL" : "TCP_DENIED",
                success ? 200 : 403,
                bytesWritten,
                "SOCKS5_CONNECT",
                target,
                "-",
                "HIER_DIRECT/" + extractHost(target),
                "-"
        );
        log(entry);
    }

    /**
     * Logs a SOCKS4 CONNECT request.
     *
     * @param clientAddress Client IP address
     * @param target        Target host:port
     * @param success       Whether connection was successful
     * @param durationMs    Request duration in milliseconds
     * @param bytesWritten  Total bytes transferred
     */
    public void logSocks4Connect(String clientAddress, String target, boolean success,
                                  long durationMs, long bytesWritten) {
        AccessLogEntry entry = new AccessLogEntry(
                LocalDateTime.now(),
                durationMs,
                clientAddress,
                success ? "TCP_TUNNEL" : "TCP_DENIED",
                success ? 200 : 403,
                bytesWritten,
                "SOCKS4_CONNECT",
                target,
                "-",
                "HIER_DIRECT/" + extractHost(target),
                "-"
        );
        log(entry);
    }

    /**
     * Logs an HTTP forward request (non-CONNECT).
     *
     * @param clientAddress Client IP address
     * @param method        HTTP method (GET, POST, etc.)
     * @param uri           Request URI
     * @param statusCode    Response status code
     * @param durationMs    Request duration in milliseconds
     * @param bytesWritten  Response size in bytes
     * @param contentType   Response content type
     */
    public void logHttpForward(String clientAddress, String method, String uri,
                                int statusCode, long durationMs, long bytesWritten,
                                String contentType) {
        AccessLogEntry entry = new AccessLogEntry(
                LocalDateTime.now(),
                durationMs,
                clientAddress,
                "TCP_MISS",
                statusCode,
                bytesWritten,
                method,
                uri,
                "-",
                "HIER_DIRECT/" + extractHostFromUri(uri),
                contentType != null ? contentType : "-"
        );
        log(entry);
    }

    /**
     * Formats a log entry as a Squid-style line.
     */
    private String formatEntry(AccessLogEntry entry) {
        // Format: timestamp duration client action/code size method URL user hierarchy content-type
        return String.format("%s %d %s %s/%d %d %s %s %s %s %s",
                TIMESTAMP_FORMAT.format(entry.timestamp()),
                entry.durationMs(),
                entry.clientAddress(),
                entry.action(),
                entry.statusCode(),
                entry.bytesWritten(),
                entry.method(),
                entry.uri(),
                entry.user(),
                entry.hierarchy(),
                entry.contentType()
        );
    }

    /**
     * Extracts host from host:port format.
     */
    private String extractHost(String target) {
        int colonIdx = target.lastIndexOf(':');
        return colonIdx > 0 ? target.substring(0, colonIdx) : target;
    }

    /**
     * Extracts host from a full URI.
     */
    private String extractHostFromUri(String uri) {
        try {
            if (uri.startsWith("http://")) {
                String afterScheme = uri.substring(7);
                int slashIdx = afterScheme.indexOf('/');
                return slashIdx > 0 ? afterScheme.substring(0, slashIdx) : afterScheme;
            } else if (uri.startsWith("https://")) {
                String afterScheme = uri.substring(8);
                int slashIdx = afterScheme.indexOf('/');
                return slashIdx > 0 ? afterScheme.substring(0, slashIdx) : afterScheme;
            }
            return uri;
        } catch (Exception e) {
            return uri;
        }
    }

    /**
     * Async writer loop - pulls entries from queue and writes to outputs.
     */
    private void writeLoop() {
        while (running.get() || !logQueue.isEmpty()) {
            try {
                String line = logQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (line != null) {
                    writeLine(line);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Flush remaining entries
        logQueue.forEach(this::writeLine);
    }

    /**
     * Writes a line to configured outputs.
     */
    private void writeLine(String line) {
        // Write to file if configured
        if (fileWriter != null) {
            fileWriter.println(line);
        }

        // Write to console if enabled
        if (consoleOutput) {
            System.out.println(line);
        }
    }

    /**
     * Closes the access log and flushes remaining entries.
     */
    @Override
    public void close() {
        running.set(false);
        try {
            writerThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (fileWriter != null) {
            fileWriter.close();
        }
    }

    /**
     * Access log entry record.
     */
    public record AccessLogEntry(
            LocalDateTime timestamp,
            long durationMs,
            String clientAddress,
            String action,
            int statusCode,
            long bytesWritten,
            String method,
            String uri,
            String user,
            String hierarchy,
            String contentType
    ) {
    }
}
