package xzy.fz.handler.upstream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xzy.fz.config.Config;
import xzy.fz.handler.RelayHandler;

import java.nio.charset.StandardCharsets;

/**
 * Handles HTTP CONNECT response from upstream proxy for HTTP proxy requests.
 * <p>
 * This handler is used when the client sends an HTTP CONNECT request.
 * It performs the following:
 * <ol>
 *   <li>Sends CONNECT request to upstream proxy on channel activation</li>
 *   <li>Waits for upstream's 200 response</li>
 *   <li>On success, sends 200 to client and switches both channels to relay mode</li>
 *   <li>On failure, forwards error to client and closes connections</li>
 * </ol>
 *
 * <h2>Pipeline Transformation:</h2>
 * After successful CONNECT, both client and upstream pipelines are stripped
 * of HTTP codecs and replaced with {@link RelayHandler} for raw byte forwarding.
 */
public class HttpConnectHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
    private static final Logger log = LoggerFactory.getLogger(HttpConnectHandler.class);

    private final ChannelHandlerContext clientCtx;
    private final String targetHost;
    private final int targetPort;
    private final Config config;

    /**
     * Creates a new HTTP CONNECT handler.
     *
     * @param clientCtx  Client channel context (for sending responses)
     * @param targetHost Target host from CONNECT request
     * @param targetPort Target port from CONNECT request
     * @param config     Proxy configuration
     */
    public HttpConnectHandler(ChannelHandlerContext clientCtx, String targetHost,
                              int targetPort, Config config) {
        this.clientCtx = clientCtx;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.config = config;
    }

    /**
     * Called when upstream connection is established.
     * Sends CONNECT request to upstream proxy.
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // Build CONNECT request for upstream proxy
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

        log.debug("Sending CONNECT to upstream for {}:{}", targetHost, targetPort);
        ctx.writeAndFlush(connectRequest);
    }

    /**
     * Handles upstream proxy's response to CONNECT request.
     */
    @Override
    protected void channelRead0(ChannelHandlerContext upstreamCtx, FullHttpResponse response) {
        if (response.status().code() == 200) {
            log.debug("Upstream CONNECT successful for {}:{}", targetHost, targetPort);

            // Send 200 Connection Established to client
            FullHttpResponse clientResponse = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "Connection Established"));
            clientResponse.headers()
                    .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
                    .set("Proxy-Connection", "keep-alive");
            clientCtx.writeAndFlush(clientResponse);

            // Switch both channels to raw byte relay mode
            switchToRelayMode(upstreamCtx);
        } else {
            log.error("Upstream CONNECT failed with status: {}", response.status());

            // Forward upstream error to client
            FullHttpResponse errorResponse = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, response.status());
            errorResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            clientCtx.writeAndFlush(errorResponse).addListener(ChannelFutureListener.CLOSE);
            upstreamCtx.close();
        }
    }

    /**
     * Transforms both pipelines for raw byte relay.
     * Removes HTTP codecs and adds RelayHandlers.
     */
    private void switchToRelayMode(ChannelHandlerContext upstreamCtx) {
        Channel clientChannel = clientCtx.channel();
        Channel upstreamChannel = upstreamCtx.channel();

        // Remove HTTP codecs from client pipeline
        removeHandlerSafely(clientChannel.pipeline(), HttpRequestDecoder.class);
        removeHandlerSafely(clientChannel.pipeline(), HttpResponseEncoder.class);
        removeHandlerSafely(clientChannel.pipeline(), "http-proxy-handler");

        // Remove HTTP codecs from upstream pipeline
        removeHandlerSafely(upstreamChannel.pipeline(), HttpClientCodec.class);
        removeHandlerSafely(upstreamChannel.pipeline(), HttpObjectAggregator.class);
        upstreamChannel.pipeline().remove(this);

        // Add relay handlers for bidirectional byte forwarding
        clientChannel.pipeline().addLast("relay", new RelayHandler(upstreamChannel));
        upstreamChannel.pipeline().addLast("relay", new RelayHandler(clientChannel));

        log.debug("Switched to relay mode for {}:{}", targetHost, targetPort);
    }

    /**
     * Safely removes a handler from the pipeline.
     * Catches exceptions if handler doesn't exist.
     */
    private void removeHandlerSafely(ChannelPipeline pipeline, Class<? extends ChannelHandler> handlerType) {
        try {
            pipeline.remove(handlerType);
        } catch (Exception ignored) {
            // Handler may not exist in pipeline
        }
    }

    private void removeHandlerSafely(ChannelPipeline pipeline, String handlerName) {
        try {
            pipeline.remove(handlerName);
        } catch (Exception ignored) {
            // Handler may not exist in pipeline
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Upstream connection error: {}", cause.getMessage());
        sendErrorToClient(HttpResponseStatus.BAD_GATEWAY, "Upstream connection failed");
        ctx.close();
    }

    /**
     * Sends an error response to the client.
     */
    private void sendErrorToClient(HttpResponseStatus status, String message) {
        if (clientCtx.channel().isActive()) {
            ByteBuf content = Unpooled.copiedBuffer(
                    "<html><body><h1>" + message + "</h1></body></html>", StandardCharsets.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, status, content);
            response.headers()
                    .set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8")
                    .set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
                    .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            clientCtx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
