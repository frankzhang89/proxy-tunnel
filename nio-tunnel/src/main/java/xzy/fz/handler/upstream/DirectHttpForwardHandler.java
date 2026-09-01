package xzy.fz.handler.upstream;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xzy.fz.handler.RelayHandler;
import xzy.fz.log.AccessLog;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Forwards a plain HTTP request directly to the target server, bypassing the upstream proxy.
 * <p>
 * Used when the target host matches a {@code no.proxy.hosts} pattern and the request is a
 * regular HTTP method (GET, POST, etc.) rather than CONNECT.
 * <p>
 * Compared to {@link HttpForwardHandler}, this handler:
 * <ul>
 *   <li>Connects directly to the target server (no upstream proxy)</li>
 *   <li>Converts the absolute proxy-format URI (e.g., {@code http://example.com/path}) to a relative path ({@code /path})</li>
 *   <li>Does not add a {@code Proxy-Authorization} header</li>
 *   <li>Strips {@code Proxy-Connection} and {@code Proxy-Authorization} headers before forwarding</li>
 * </ul>
 */
public class DirectHttpForwardHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
    private static final Logger log = LoggerFactory.getLogger(DirectHttpForwardHandler.class);

    private final ChannelHandlerContext clientCtx;
    private final HttpRequest originalRequest;
    /** Relative URI path (e.g., {@code /path?q=1}) to use in the forwarded request. */
    private final String relativeUri;
    private final AccessLog accessLog;
    private final long startTime;
    private final String clientAddress;
    private final boolean upgradeRequested;

    /**
     * @param clientCtx       Client channel context
     * @param originalRequest Original proxy HTTP request from the client
     * @param relativeUri     Relative URI to send to the target server (without scheme://host:port)
     * @param accessLog       Access log instance (may be null)
     * @param startTime       Request start time for duration calculation
     * @param clientAddress   Client IP address for logging
     */
    public DirectHttpForwardHandler(ChannelHandlerContext clientCtx, HttpRequest originalRequest,
                                     String relativeUri, AccessLog accessLog,
                                     long startTime, String clientAddress) {
        this.clientCtx = clientCtx;
        this.originalRequest = originalRequest;
        this.relativeUri = relativeUri;
        this.accessLog = accessLog;
        this.startTime = startTime;
        this.clientAddress = clientAddress;
        this.upgradeRequested = originalRequest.headers().contains(HttpHeaderNames.UPGRADE);
    }

    /**
     * Called when the direct connection to the target server is established.
     * Sends the HTTP request with a relative URI.
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        DefaultFullHttpRequest forwardRequest = new DefaultFullHttpRequest(
                originalRequest.protocolVersion(),
                originalRequest.method(),
                relativeUri);

        // Copy headers, stripping proxy-specific ones
        for (Map.Entry<String, String> header : originalRequest.headers()) {
            String key = header.getKey();
            if (!key.equalsIgnoreCase("Proxy-Authorization") &&
                    !key.equalsIgnoreCase("Proxy-Connection")) {
                forwardRequest.headers().set(key, header.getValue());
            }
        }

        log.debug("Direct forwarding {} {} to server", originalRequest.method(), relativeUri);
        ctx.writeAndFlush(forwardRequest);
    }

    /**
     * Receives the HTTP response from the target server and relays it to the client.
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
        FullHttpResponse clientResponse = new DefaultFullHttpResponse(
                response.protocolVersion(),
                response.status(),
                response.content().retain());
        clientResponse.headers().set(response.headers());

        long duration = System.currentTimeMillis() - startTime;
        int contentLength = response.content().readableBytes();
        String contentType = response.headers().get(HttpHeaderNames.CONTENT_TYPE);
        logAccess(response.status().code(), duration, contentLength, contentType);

        log.debug("Direct forwarding response {} to client", response.status());
        ChannelFuture clientWrite = clientCtx.writeAndFlush(clientResponse);

        if (upgradeRequested && response.status().code() == HttpResponseStatus.SWITCHING_PROTOCOLS.code()) {
            log.debug("Direct HTTP protocol upgrade accepted; switching to raw relay mode");
            switchToRelayMode(ctx);
        } else {
            clientWrite.addListener(future -> ctx.close());
        }
    }

    /**
     * Removes HTTP codecs after a successful protocol upgrade and relays all
     * subsequent bytes bidirectionally.
     */
    private void switchToRelayMode(ChannelHandlerContext targetCtx) {
        Channel clientChannel = clientCtx.channel();
        Channel targetChannel = targetCtx.channel();

        removeHandlerSafely(clientChannel.pipeline(), HttpRequestDecoder.class);
        removeHandlerSafely(clientChannel.pipeline(), HttpResponseEncoder.class);
        removeHandlerSafely(clientChannel.pipeline(), "http-proxy-handler");

        targetChannel.pipeline().remove(this);

        clientChannel.pipeline().addLast("relay", new RelayHandler(targetChannel));
        targetChannel.pipeline().addLast("relay", new RelayHandler(clientChannel));

        // Preserve WebSocket bytes that may have arrived in the same TCP packet
        // as the 101 response. The decoder forwards them during this read cycle.
        targetChannel.eventLoop().execute(() -> {
            removeHandlerSafely(targetChannel.pipeline(), HttpClientCodec.class);
            removeHandlerSafely(targetChannel.pipeline(), HttpObjectAggregator.class);
        });
    }

    private void removeHandlerSafely(ChannelPipeline pipeline,
                                     Class<? extends ChannelHandler> handlerType) {
        try {
            pipeline.remove(handlerType);
        } catch (Exception ignored) {
            // Handler may not be present in a test pipeline or may already be removed.
        }
    }

    private void removeHandlerSafely(ChannelPipeline pipeline, String handlerName) {
        try {
            pipeline.remove(handlerName);
        } catch (Exception ignored) {
            // Handler may not be present in a test pipeline or may already be removed.
        }
    }

    private void logAccess(int statusCode, long duration, long bytesWritten, String contentType) {
        if (accessLog != null) {
            accessLog.logHttpForward(clientAddress, originalRequest.method().name(),
                    originalRequest.uri(), statusCode, duration, bytesWritten, contentType);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Direct HTTP forward error: {}", cause.getMessage());
        ctx.close();
        if (clientCtx.channel().isActive()) {
            byte[] body = "<html><body><h1>Bad Gateway</h1></body></html>".getBytes(StandardCharsets.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY,
                    Unpooled.wrappedBuffer(body));
            response.headers()
                    .set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8")
                    .set(HttpHeaderNames.CONTENT_LENGTH, body.length)
                    .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            clientCtx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
