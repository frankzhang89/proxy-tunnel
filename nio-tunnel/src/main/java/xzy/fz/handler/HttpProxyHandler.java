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
import xzy.fz.handler.upstream.DirectHttpForwardHandler;
import xzy.fz.handler.upstream.DirectTunnelHandler;
import xzy.fz.handler.upstream.HttpConnectHandler;
import xzy.fz.handler.upstream.HttpForwardHandler;
import xzy.fz.log.AccessLog;

import java.net.URI;
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
    private final AccessLog accessLog;

    /**
     * Creates a new HTTP proxy handler.
     *
     * @param config     Proxy configuration
     * @param sslContext SSL context for upstream TLS connections (may be null if TLS disabled)
     * @param accessLog  Access log for Squid-style logging (may be null if disabled)
     */
    public HttpProxyHandler(Config config, SslContext sslContext, AccessLog accessLog) {
        this.config = config;
        this.sslContext = sslContext;
        this.accessLog = accessLog;
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
     * If the target host matches a no-proxy pattern, connects directly to the target.
     * Otherwise connects via the upstream HTTPS proxy.
     */
    private void handleConnect(ChannelHandlerContext ctx, HttpRequest request) {
        // Parse target from CONNECT request (e.g., "example.com:443")
        String target = request.uri();
        String[] parts = target.split(":");
        String targetHost = parts[0];
        int targetPort = parts.length > 1 ? Integer.parseInt(parts[1]) : 443;

        // Capture start time for access log
        long startTime = System.currentTimeMillis();
        String clientAddress = extractClientAddress(ctx);

        if (config.noProxyMatcher().matches(targetHost)) {
            log.info("CONNECT {} DIRECT (no-proxy)", target);
            handleConnectDirect(ctx, targetHost, targetPort, startTime, clientAddress);
        } else {
            log.info("CONNECT {} via upstream {}:{}", target, config.upstreamHost(), config.upstreamPort());
            handleConnectViaUpstream(ctx, targetHost, targetPort, startTime, clientAddress);
        }
    }

    /**
     * Connects directly to the target for a CONNECT request (bypassing upstream proxy).
     */
    private void handleConnectDirect(ChannelHandlerContext ctx, String targetHost, int targetPort,
                                      long startTime, String clientAddress) {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(ctx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMillis())
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new DirectTunnelHandler(
                                ctx,
                                () -> {
                                    // Send 200 Connection Established to client
                                    FullHttpResponse response = new DefaultFullHttpResponse(
                                            HttpVersion.HTTP_1_1,
                                            new HttpResponseStatus(200, "Connection Established"));
                                    response.headers()
                                            .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
                                            .set("Proxy-Connection", "keep-alive");
                                    ctx.writeAndFlush(response);
                                },
                                () -> sendError(ctx, HttpResponseStatus.BAD_GATEWAY,
                                        "Failed to connect directly to " + targetHost),
                                pipeline -> {
                                    removeHandlerSafely(pipeline, HttpRequestDecoder.class);
                                    removeHandlerSafely(pipeline, HttpResponseEncoder.class);
                                    removeHandlerSafely(pipeline, "http-proxy-handler");
                                },
                                accessLog, startTime, clientAddress, targetHost, targetPort
                        ));
                    }
                });

        bootstrap.connect(targetHost, targetPort).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                log.error("Direct CONNECT to {} failed: {}", targetHost + ":" + targetPort,
                        future.cause().getMessage());
                sendError(ctx, HttpResponseStatus.BAD_GATEWAY,
                        "Failed to connect directly to " + targetHost);
            }
        });
    }

    /**
     * Connects to the target via the upstream HTTPS proxy for a CONNECT request.
     */
    private void handleConnectViaUpstream(ChannelHandlerContext ctx, String targetHost, int targetPort,
                                           long startTime, String clientAddress) {
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
                        p.addLast(new HttpConnectHandler(ctx, targetHost, targetPort, config,
                                accessLog, startTime, clientAddress));
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
     * If the target host matches a no-proxy pattern, connects directly to the server.
     * Otherwise forwards via the upstream proxy.
     */
    private void handleHttpForward(ChannelHandlerContext ctx, HttpRequest request) {
        // Capture start time for access log
        long startTime = System.currentTimeMillis();
        String clientAddress = extractClientAddress(ctx);

        // Try to extract the target host from the request URI for no-proxy check
        String targetHost = extractHostFromAbsoluteUri(request.uri());
        if (targetHost != null && config.noProxyMatcher().matches(targetHost)) {
            log.info("{} {} DIRECT (no-proxy)", request.method(), request.uri());
            handleHttpForwardDirect(ctx, request, startTime, clientAddress);
        } else {
            log.info("{} {} via upstream {}:{}",
                    request.method(), request.uri(), config.upstreamHost(), config.upstreamPort());
            handleHttpForwardViaUpstream(ctx, request, startTime, clientAddress);
        }
    }

    /**
     * Forwards a plain HTTP request directly to the target server (no upstream proxy).
     */
    private void handleHttpForwardDirect(ChannelHandlerContext ctx, HttpRequest request,
                                          long startTime, String clientAddress) {
        String host;
        int port;
        String relativeUri;
        try {
            URI uri = new URI(request.uri());
            host = uri.getHost();
            port = uri.getPort() == -1 ? 80 : uri.getPort();
            String path = uri.getRawPath();
            String query = uri.getRawQuery();
            relativeUri = (path == null || path.isEmpty()) ? "/" :
                    (query != null ? path + "?" + query : path);
        } catch (Exception e) {
            log.error("Failed to parse URI for direct forward: {}", request.uri());
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "Invalid request URI");
            return;
        }

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(ctx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMillis())
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new HttpClientCodec());
                        p.addLast(new HttpObjectAggregator(config.httpMaxInitialBytes()));
                        p.addLast(new DirectHttpForwardHandler(ctx, request, relativeUri,
                                accessLog, startTime, clientAddress));
                    }
                });

        final String finalHost = host;
        final int finalPort = port;
        bootstrap.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                log.error("Direct HTTP forward to {}:{} failed: {}", finalHost, finalPort,
                        future.cause().getMessage());
                sendError(ctx, HttpResponseStatus.BAD_GATEWAY,
                        "Failed to connect directly to " + finalHost);
            }
        });
    }

    /**
     * Forwards a plain HTTP request to the upstream proxy.
     */
    private void handleHttpForwardViaUpstream(ChannelHandlerContext ctx, HttpRequest request,
                                               long startTime, String clientAddress) {
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
                        p.addLast(new HttpForwardHandler(ctx, request, config,
                                accessLog, startTime, clientAddress));
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

    /**
     * Extracts the hostname from an absolute HTTP URI (e.g., {@code http://example.com:8080/path}).
     * Returns null if parsing fails or the URI is not absolute.
     */
    private static String extractHostFromAbsoluteUri(String uri) {
        try {
            URI parsed = new URI(uri);
            return parsed.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Safely removes a handler from the pipeline by type.
     */
    private static void removeHandlerSafely(ChannelPipeline pipeline,
                                             Class<? extends ChannelHandler> handlerType) {
        try {
            pipeline.remove(handlerType);
        } catch (Exception ignored) {
            // Handler may not exist in pipeline
        }
    }

    /**
     * Safely removes a handler from the pipeline by name.
     */
    private static void removeHandlerSafely(ChannelPipeline pipeline, String handlerName) {
        try {
            pipeline.remove(handlerName);
        } catch (Exception ignored) {
            // Handler may not exist in pipeline
        }
    }

    /**
     * Extracts the client IP address from the channel context.
     *
     * @param ctx Channel context
     * @return Client IP address as string
     */
    private String extractClientAddress(ChannelHandlerContext ctx) {
        try {
            java.net.SocketAddress remoteAddress = ctx.channel().remoteAddress();
            if (remoteAddress instanceof java.net.InetSocketAddress inetAddr) {
                return inetAddr.getAddress().getHostAddress();
            }
            return remoteAddress != null ? remoteAddress.toString() : "-";
        } catch (Exception e) {
            return "-";
        }
    }
}
