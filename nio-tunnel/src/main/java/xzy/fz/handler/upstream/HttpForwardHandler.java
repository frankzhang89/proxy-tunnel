package xzy.fz.handler.upstream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xzy.fz.config.Config;
import xzy.fz.log.AccessLog;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Handles forwarding regular HTTP requests (non-CONNECT) to upstream proxy.
 * <p>
 * This handler is used for HTTP methods like GET, POST, PUT, DELETE, etc.
 * It forwards the request to the upstream proxy and relays the response back.
 *
 * <h2>Flow:</h2>
 * <ol>
 *   <li>On channel activation, forwards modified request to upstream</li>
 *   <li>Receives response from upstream</li>
 *   <li>Forwards response to client</li>
 *   <li>Closes the upstream connection</li>
 * </ol>
 *
 * <h2>Request Modifications:</h2>
 * <ul>
 *   <li>Removes client's Proxy-Authorization header</li>
 *   <li>Adds upstream Proxy-Authorization if configured</li>
 *   <li>Adds Proxy-Connection: keep-alive header</li>
 * </ul>
 */
public class HttpForwardHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
    private static final Logger log = LoggerFactory.getLogger(HttpForwardHandler.class);

    private final ChannelHandlerContext clientCtx;
    private final HttpRequest originalRequest;
    private final Config config;
    private final AccessLog accessLog;
    private final long startTime;
    private final String clientAddress;

    /**
     * Creates a new HTTP forward handler.
     *
     * @param clientCtx       Client channel context
     * @param originalRequest Original HTTP request from client
     * @param config          Proxy configuration
     * @param accessLog       Access log instance (may be null)
     * @param startTime       Request start time for duration calculation
     * @param clientAddress   Client IP address for logging
     */
    public HttpForwardHandler(ChannelHandlerContext clientCtx, HttpRequest originalRequest,
                               Config config, AccessLog accessLog, long startTime, String clientAddress) {
        this.clientCtx = clientCtx;
        this.originalRequest = originalRequest;
        this.config = config;
        this.accessLog = accessLog;
        this.startTime = startTime;
        this.clientAddress = clientAddress;
    }

    /**
     * Called when upstream connection is established.
     * Forwards the modified HTTP request to upstream.
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // Build the forwarded request
        DefaultFullHttpRequest forwardRequest = new DefaultFullHttpRequest(
                originalRequest.protocolVersion(),
                originalRequest.method(),
                originalRequest.uri());

        // Copy headers, filtering out client proxy-auth
        for (Map.Entry<String, String> header : originalRequest.headers()) {
            if (!header.getKey().equalsIgnoreCase("Proxy-Authorization")) {
                forwardRequest.headers().set(header.getKey(), header.getValue());
            }
        }

        // Add upstream authentication if configured
        if (config.expectedUpstreamAuthHeader() != null) {
            forwardRequest.headers().set(HttpHeaderNames.PROXY_AUTHORIZATION,
                    config.expectedUpstreamAuthHeader());
        }

        // Add proxy-connection header
        forwardRequest.headers().set("Proxy-Connection", "keep-alive");

        log.debug("Forwarding {} {} to upstream", originalRequest.method(), originalRequest.uri());
        ctx.writeAndFlush(forwardRequest);
    }

    /**
     * Receives response from upstream and forwards to client.
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
        // Forward response to client
        // Note: retain() the content because we're passing it to another channel
        FullHttpResponse clientResponse = new DefaultFullHttpResponse(
                response.protocolVersion(),
                response.status(),
                response.content().retain());
        clientResponse.headers().set(response.headers());

        // Log access
        long duration = System.currentTimeMillis() - startTime;
        int contentLength = response.content().readableBytes();
        String contentType = response.headers().get(HttpHeaderNames.CONTENT_TYPE);
        logAccess(response.status().code(), duration, contentLength, contentType);

        log.debug("Forwarding response {} to client", response.status());

        clientCtx.writeAndFlush(clientResponse).addListener(future -> {
            // Close upstream connection after response is sent
            ctx.close();
        });
    }

    /**
     * Logs access to the access log.
     */
    private void logAccess(int statusCode, long duration, long bytesWritten, String contentType) {
        if (accessLog != null) {
            accessLog.logHttpForward(clientAddress, originalRequest.method().name(),
                    originalRequest.uri(), statusCode, duration, bytesWritten, contentType);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("HTTP forward error: {}", cause.getMessage());
        ctx.close();

        // Send error to client if still active
        if (clientCtx.channel().isActive()) {
            ByteBuf content = Unpooled.copiedBuffer(
                    "<html><body><h1>Bad Gateway</h1></body></html>", StandardCharsets.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY, content);
            response.headers()
                    .set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8")
                    .set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
                    .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            clientCtx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
