package xzy.fz.handler.upstream;

import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.socksx.v4.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xzy.fz.config.Config;
import xzy.fz.handler.RelayHandler;
import xzy.fz.log.AccessLog;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles upstream proxy response for SOCKS4 CONNECT requests.
 * <p>
 * This handler is used when a SOCKS4 client sends a CONNECT command.
 * It sends an HTTP CONNECT to the upstream proxy and translates the
 * response back to SOCKS4 protocol.
 *
 * <h2>Flow:</h2>
 * <ol>
 *   <li>On channel activation, sends HTTP CONNECT to upstream</li>
 *   <li>Receives 200 response from upstream</li>
 *   <li>Sends SOCKS4 success response to client</li>
 *   <li>Switches both channels to relay mode</li>
 * </ol>
 */
public class Socks4ConnectHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
    private static final Logger log = LoggerFactory.getLogger(Socks4ConnectHandler.class);

    private final ChannelHandlerContext clientCtx;
    private final String targetHost;
    private final int targetPort;
    private final Config config;
    private final AccessLog accessLog;
    private final long startTime;
    private final String clientAddress;

    /**
     * Creates a new SOCKS4 upstream connect handler.
     *
     * @param clientCtx     Client channel context
     * @param targetHost    Target host from SOCKS4 CONNECT command
     * @param targetPort    Target port from SOCKS4 CONNECT command
     * @param config        Proxy configuration
     * @param accessLog     Access log for Squid-style logging
     * @param startTime     Request start time in milliseconds
     * @param clientAddress Client address string for logging
     */
    public Socks4ConnectHandler(ChannelHandlerContext clientCtx, String targetHost,
                                int targetPort, Config config, AccessLog accessLog,
                                long startTime, String clientAddress) {
        this.clientCtx = clientCtx;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.config = config;
        this.accessLog = accessLog;
        this.startTime = startTime;
        this.clientAddress = clientAddress;
    }

    /**
     * Called when upstream connection is established.
     * Sends HTTP CONNECT request to upstream proxy.
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // Build HTTP CONNECT request
        FullHttpRequest connectRequest = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.CONNECT, targetHost + ":" + targetPort);
        connectRequest.headers()
                .set(HttpHeaderNames.HOST, targetHost + ":" + targetPort)
                .set("Proxy-Connection", "keep-alive");

        // Add upstream proxy authentication if configured
        if (config.expectedUpstreamAuthHeader() != null) {
            connectRequest.headers().set(HttpHeaderNames.PROXY_AUTHORIZATION,
                    config.expectedUpstreamAuthHeader());
        }

        log.debug("SOCKS4: Sending HTTP CONNECT to upstream for {}:{}", targetHost, targetPort);
        ctx.writeAndFlush(connectRequest);
    }

    /**
     * Handles upstream proxy's response to CONNECT request.
     */
    @Override
    protected void channelRead0(ChannelHandlerContext upstreamCtx, FullHttpResponse response) {
        if (response.status().code() == 200) {
            log.debug("SOCKS4 upstream CONNECT successful for {}:{}", targetHost, targetPort);

            // Send SOCKS4 success response to client
            clientCtx.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.SUCCESS));

            // Switch both channels to relay mode
            switchToRelayMode(upstreamCtx);
        } else {
            log.error("SOCKS4 upstream CONNECT failed: {}", response.status());

            // Log failed access
            logAccess(response.status().code(), 0);

            // Send SOCKS4 failure response to client
            clientCtx.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.REJECTED_OR_FAILED))
                    .addListener(ChannelFutureListener.CLOSE);
            upstreamCtx.close();
        }
    }

    /**
     * Transforms both pipelines for raw byte relay.
     */
    private void switchToRelayMode(ChannelHandlerContext upstreamCtx) {
        Channel clientChannel = clientCtx.channel();
        Channel upstreamChannel = upstreamCtx.channel();

        // Remove SOCKS4 decoder from client pipeline
        removeHandlerSafely(clientChannel.pipeline(), Socks4ServerDecoder.class);
        removeHandlerSafely(clientChannel.pipeline(), Socks4ServerEncoder.class);
        removeHandlerByName(clientChannel.pipeline(), "socks-unification");
        removeHandlerByName(clientChannel.pipeline(), "socks5-handler");

        // Remove HTTP codecs from upstream pipeline
        removeHandlerSafely(upstreamChannel.pipeline(), HttpClientCodec.class);
        removeHandlerSafely(upstreamChannel.pipeline(), HttpObjectAggregator.class);
        upstreamChannel.pipeline().remove(this);

        // Shared bytes counter for access logging
        AtomicLong totalBytes = new AtomicLong(0);
        AtomicBoolean logged = new AtomicBoolean(false);

        // Callback to log access when connection closes
        Runnable logCallback = () -> {
            if (logged.compareAndSet(false, true)) {
                logAccess(200, totalBytes.get());
            }
        };

        // Create relay handlers
        RelayHandler clientRelay = new RelayHandler(upstreamChannel, null);
        RelayHandler upstreamRelay = new RelayHandler(clientChannel, null);

        // Add relay handlers for bidirectional byte forwarding
        clientChannel.pipeline().addLast("relay", clientRelay);
        upstreamChannel.pipeline().addLast("relay", upstreamRelay);

        // Log access when either channel closes
        clientChannel.closeFuture().addListener(f -> {
            totalBytes.addAndGet(clientRelay.getBytesTransferred());
            totalBytes.addAndGet(upstreamRelay.getBytesTransferred());
            logCallback.run();
        });

        log.debug("SOCKS4: Switched to relay mode for {}:{}", targetHost, targetPort);
    }

    /**
     * Logs access in Squid-style format.
     */
    private void logAccess(int statusCode, long bytes) {
        if (accessLog != null) {
            long duration = System.currentTimeMillis() - startTime;
            String url = targetHost + ":" + targetPort;
            boolean success = statusCode == 200;
            accessLog.logSocks4Connect(clientAddress, url, success, duration, bytes);
        }
    }

    /**
     * Safely removes a handler from the pipeline by type.
     */
    private void removeHandlerSafely(ChannelPipeline pipeline, Class<? extends ChannelHandler> handlerType) {
        try {
            pipeline.remove(handlerType);
        } catch (Exception ignored) {
            // Handler may not exist in pipeline
        }
    }

    /**
     * Safely removes a handler from the pipeline by name.
     */
    private void removeHandlerByName(ChannelPipeline pipeline, String name) {
        try {
            pipeline.remove(name);
        } catch (Exception ignored) {
            // Handler may not exist in pipeline
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("SOCKS4 upstream error: {}", cause.getMessage());

        // Send SOCKS4 failure to client
        if (clientCtx.channel().isActive()) {
            clientCtx.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.REJECTED_OR_FAILED))
                    .addListener(ChannelFutureListener.CLOSE);
        }
        ctx.close();
    }
}
