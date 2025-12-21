package xzy.fz.handler;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xzy.fz.config.Config;
import xzy.fz.handler.upstream.HttpConnectHandler;
import xzy.fz.handler.upstream.HttpForwardHandler;

import java.nio.charset.StandardCharsets;

/**
 * Netty handler for incoming HTTP proxy requests.
 * <p>
 * This handler processes HTTP proxy requests and supports:
 * <ul>
 *   <li><b>CONNECT method</b> - For HTTPS tunneling (e.g., CONNECT example.com:443)</li>
 *   <li><b>Regular HTTP methods</b> - GET, POST, etc. for HTTP proxying</li>
 *   <li><b>PAC file serving</b> - Serves proxy auto-config file at configured path</li>
 * </ul>
 * <p>
 * All requests are forwarded to the upstream HTTPS proxy configured in {@link Config}.
 *
 * <h2>HTTP Proxy Flow (CONNECT):</h2>
 * <pre>
 * Client                  nio-tunnel               Upstream HTTPS Proxy           Target Server
 *   |                         |                            |                           |
 *   |-- CONNECT host:443 ---->|                            |                           |
 *   |                         |-- TLS + CONNECT host:443 ->|                           |
 *   |                         |<--- 200 Connection OK -----|                           |
 *   |<-- 200 Established -----|                            |                           |
 *   |                         |                            |                           |
 *   |<========== Bidirectional raw byte relay =========================================|
 * </pre>
 */
@ChannelHandler.Sharable
public class HttpProxyHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(HttpProxyHandler.class);

    private final Config config;
    private final SslContext sslContext;

    /**
     * Creates a new HTTP proxy handler.
     *
     * @param config     Proxy configuration
     * @param sslContext SSL context for upstream TLS connections (may be null if TLS disabled)
     */
    public HttpProxyHandler(Config config, SslContext sslContext) {
        this.config = config;
        this.sslContext = sslContext;
    }

    /**
     * Handles incoming channel reads.
     * Dispatches HTTP requests to appropriate handlers.
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest request) {
            handleHttpRequest(ctx, request);
        } else if (msg instanceof HttpContent) {
            // For non-CONNECT requests, content chunks are handled by the relay
            // after connection is established. Release here to prevent memory leaks.
            ReferenceCountUtil.release(msg);
        } else {
            // Pass unknown messages to next handler
            ctx.fireChannelRead(msg);
        }
    }

    /**
     * Main request routing logic.
     * Routes requests to PAC handler, CONNECT handler, or HTTP forward handler.
     */
    private void handleHttpRequest(ChannelHandlerContext ctx, HttpRequest request) {
        String uri = request.uri();
        HttpMethod method = request.method();

        log.debug("Received {} {} from {}", method, uri, ctx.channel().remoteAddress());

        // Check for PAC file request first
        if (config.pacEnabled() && uri.equals(config.pacPath()) && method == HttpMethod.GET) {
            servePacFile(ctx);
            return;
        }

        // Validate client authentication if required
        if (config.requireClientAuth()) {
            String authHeader = request.headers().get(HttpHeaderNames.PROXY_AUTHORIZATION);
            if (authHeader == null || !authHeader.equals(config.expectedClientAuthHeader())) {
                sendProxyAuthRequired(ctx);
                return;
            }
        }

        // Route to appropriate handler based on HTTP method
        if (method == HttpMethod.CONNECT) {
            // CONNECT method: establish tunnel to upstream proxy
            handleConnect(ctx, request);
        } else {
            // Regular HTTP request: forward to upstream proxy
            handleHttpForward(ctx, request);
        }
    }

    /**
     * Handles HTTP CONNECT requests for HTTPS tunneling.
     * <p>
     * Flow:
     * <ol>
     *   <li>Parse target host:port from request URI</li>
     *   <li>Connect to upstream HTTPS proxy</li>
     *   <li>Send CONNECT request to upstream</li>
     *   <li>On success, respond 200 to client and start raw byte relay</li>
     * </ol>
     */
    private void handleConnect(ChannelHandlerContext ctx, HttpRequest request) {
        // Parse target from CONNECT request (e.g., "example.com:443")
        String target = request.uri();
        String[] parts = target.split(":");
        String targetHost = parts[0];
        int targetPort = parts.length > 1 ? Integer.parseInt(parts[1]) : 443;

        log.info("CONNECT {} via upstream {}:{}", target, config.upstreamHost(), config.upstreamPort());

        // Create bootstrap for upstream connection
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(ctx.channel().eventLoop())  // Use same event loop as client
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMillis())
                .option(ChannelOption.TCP_NODELAY, true)  // Disable Nagle for lower latency
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        // Add SSL handler if upstream requires TLS
                        if (sslContext != null) {
                            p.addLast(sslContext.newHandler(ch.alloc(),
                                    config.upstreamHost(), config.upstreamPort()));
                        }
                        // HTTP codec for initial CONNECT handshake with upstream
                        p.addLast(new HttpClientCodec());
                        // Aggregate full HTTP response for CONNECT result
                        p.addLast(new HttpObjectAggregator(65536));
                        // Handler for upstream CONNECT response
                        p.addLast(new HttpConnectHandler(ctx, targetHost, targetPort, config));
                    }
                });

        // Connect to upstream proxy
        ChannelFuture connectFuture = bootstrap.connect(config.upstreamHost(), config.upstreamPort());
        connectFuture.addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                log.error("Failed to connect to upstream proxy: {}", future.cause().getMessage());
                sendError(ctx, HttpResponseStatus.BAD_GATEWAY, "Failed to connect to upstream proxy");
            }
        });
    }

    /**
     * Handles regular HTTP requests (GET, POST, etc.).
     * Forwards the request to upstream proxy and relays the response back.
     */
    private void handleHttpForward(ChannelHandlerContext ctx, HttpRequest request) {
        log.info("{} {} via upstream {}:{}",
                request.method(), request.uri(), config.upstreamHost(), config.upstreamPort());

        // Create bootstrap for upstream connection
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(ctx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMillis())
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        if (sslContext != null) {
                            p.addLast(sslContext.newHandler(ch.alloc(),
                                    config.upstreamHost(), config.upstreamPort()));
                        }
                        // HTTP codec for upstream communication
                        p.addLast(new HttpClientCodec());
                        // Aggregator for response handling
                        p.addLast(new HttpObjectAggregator(config.httpMaxInitialBytes()));
                        // Handler to forward request and relay response
                        p.addLast(new HttpForwardHandler(ctx, request, config));
                    }
                });

        bootstrap.connect(config.upstreamHost(), config.upstreamPort())
                .addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        log.error("Failed to connect to upstream: {}", future.cause().getMessage());
                        sendError(ctx, HttpResponseStatus.BAD_GATEWAY, "Failed to connect to upstream proxy");
                    }
                });
    }

    /**
     * Serves the PAC (Proxy Auto-Config) file.
     * <p>
     * PAC files allow browsers and applications to automatically configure
     * proxy settings. The file is generated dynamically based on configuration
     * or loaded from a custom file if specified.
     */
    private void servePacFile(ChannelHandlerContext ctx) {
        String pacContent = config.pacContent();
        ByteBuf content = Unpooled.copiedBuffer(pacContent, StandardCharsets.UTF_8);

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "application/x-ns-proxy-autoconfig")
                .set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        log.debug("Served PAC file to {}", ctx.channel().remoteAddress());
    }

    /**
     * Sends HTTP 407 Proxy Authentication Required response.
     */
    private void sendProxyAuthRequired(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED);
        response.headers()
                .set(HttpHeaderNames.PROXY_AUTHENTICATE, "Basic realm=\"" + config.serverName() + "\"")
                .set(HttpHeaderNames.CONTENT_LENGTH, 0)
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        log.debug("Sent 407 Proxy Auth Required to {}", ctx.channel().remoteAddress());
    }

    /**
     * Sends an HTTP error response to the client.
     *
     * @param ctx     Channel context
     * @param status  HTTP status code
     * @param message Error message to display
     */
    public static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        ByteBuf content = Unpooled.copiedBuffer(
                "<html><body><h1>" + message + "</h1></body></html>", StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, content);
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8")
                .set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Handles idle timeout events - closes connections that have been idle too long.
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent idleEvent) {
            if (idleEvent.state() == IdleState.ALL_IDLE) {
                log.debug("Closing idle connection: {}", ctx.channel().remoteAddress());
                ctx.close();
            }
        }
    }

    /**
     * Handles exceptions by logging and closing the connection.
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("Exception in HTTP handler: {}", cause.getMessage());
        ctx.close();
    }
}
