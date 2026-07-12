package xzy.fz.handler.upstream;

import io.netty.channel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xzy.fz.handler.RelayHandler;
import xzy.fz.log.AccessLog;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Establishes a direct TCP tunnel to the target host, bypassing the upstream proxy.
 * <p>
 * Used when the target host matches a {@code no.proxy.hosts} pattern.
 * On channel activation, calls the provided success callback (which sends the protocol-specific
 * success response to the client), then switches both channels into raw byte relay mode.
 *
 * <h2>Protocol support:</h2>
 * The caller supplies protocol-specific callbacks:
 * <ul>
 *   <li><b>HTTP CONNECT</b> - success sends {@code 200 Connection Established}</li>
 *   <li><b>SOCKS5</b> - success sends {@code Socks5CommandResponse SUCCESS}</li>
 *   <li><b>SOCKS4</b> - success sends {@code Socks4CommandResponse SUCCESS}</li>
 * </ul>
 */
public class DirectTunnelHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(DirectTunnelHandler.class);

    private final ChannelHandlerContext clientCtx;
    /** Called on channel activation to send the protocol-specific success response to the client. */
    private final Runnable onSuccess;
    /** Called when direct connection fails, to send the protocol-specific failure response to the client. */
    private final Runnable onFailure;
    /** Removes protocol-specific decoders/encoders from the client pipeline before relay mode. */
    private final Consumer<ChannelPipeline> clientPipelineCleanup;
    private final AccessLog accessLog;
    private final long startTime;
    private final String clientAddress;
    private final String targetHost;
    private final int targetPort;

    /**
     * @param clientCtx             Client channel context
     * @param onSuccess             Callback to send success response to client (e.g. 200 or SOCKS SUCCESS)
     * @param onFailure             Callback to send failure response to client
     * @param clientPipelineCleanup Removes protocol handlers from the client pipeline before relay
     * @param accessLog             Access log instance (may be null)
     * @param startTime             Request start time for duration calculation
     * @param clientAddress         Client IP address for logging
     * @param targetHost            Target hostname or IP
     * @param targetPort            Target port
     */
    public DirectTunnelHandler(ChannelHandlerContext clientCtx,
                                Runnable onSuccess,
                                Runnable onFailure,
                                Consumer<ChannelPipeline> clientPipelineCleanup,
                                AccessLog accessLog, long startTime,
                                String clientAddress, String targetHost, int targetPort) {
        this.clientCtx = clientCtx;
        this.onSuccess = onSuccess;
        this.onFailure = onFailure;
        this.clientPipelineCleanup = clientPipelineCleanup;
        this.accessLog = accessLog;
        this.startTime = startTime;
        this.clientAddress = clientAddress;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    /**
     * Called when the direct TCP connection to the target is established.
     * Notifies the client of success and switches both channels to relay mode.
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.debug("Direct connection established to {}:{}", targetHost, targetPort);
        onSuccess.run();
        switchToRelayMode(ctx);
    }

    private void switchToRelayMode(ChannelHandlerContext ctx) {
        Channel clientChannel = clientCtx.channel();
        Channel targetChannel = ctx.channel();

        // Remove protocol-specific handlers from the client pipeline
        clientPipelineCleanup.accept(clientChannel.pipeline());

        // Remove this handler from the target pipeline
        targetChannel.pipeline().remove(this);

        AtomicLong totalBytes = new AtomicLong(0);
        AtomicBoolean logged = new AtomicBoolean(false);
        Runnable logCallback = () -> {
            if (logged.compareAndSet(false, true)) {
                logAccess(200, totalBytes.get());
            }
        };

        RelayHandler clientRelay = new RelayHandler(targetChannel, null);
        RelayHandler targetRelay = new RelayHandler(clientChannel, null);

        clientChannel.pipeline().addLast("relay", clientRelay);
        targetChannel.pipeline().addLast("relay", targetRelay);

        clientChannel.closeFuture().addListener(f -> {
            totalBytes.addAndGet(clientRelay.getBytesTransferred());
            totalBytes.addAndGet(targetRelay.getBytesTransferred());
            logCallback.run();
        });

        log.debug("Direct relay mode active for {}:{}", targetHost, targetPort);
    }

    private void logAccess(int statusCode, long bytes) {
        if (accessLog != null) {
            long duration = System.currentTimeMillis() - startTime;
            accessLog.logConnect(clientAddress, targetHost + ":" + targetPort, statusCode, duration, bytes);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Direct connection to {}:{} failed: {}", targetHost, targetPort, cause.getMessage());
        onFailure.run();
        ctx.close();
    }
}
