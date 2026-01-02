package xzy.fz.handler;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import io.netty.handler.codec.socksx.v4.*;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.handler.ssl.SslContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xzy.fz.config.Config;
import xzy.fz.config.ConfigLoader;
import xzy.fz.handler.upstream.Socks4ConnectHandler;
import xzy.fz.handler.upstream.Socks5ConnectHandler;
import xzy.fz.log.AccessLog;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Netty handler for SOCKS4 and SOCKS5 proxy protocols.
 * <p>
 * This handler implements both SOCKS4 (RFC 1928) and SOCKS5 protocols,
 * forwarding connections to the upstream HTTPS proxy using HTTP CONNECT.
 * <p>
 * The {@link SocksPortUnificationServerHandler} in the pipeline automatically
 * detects the SOCKS version and routes to appropriate decoders.
 *
 * <h2>SOCKS4 vs SOCKS5:</h2>
 * <pre>
 * Client                    nio-tunnel                 Upstream HTTPS Proxy
 *   |                           |                              |
 *   |-- Initial (auth methods)->|                              |
 *   |<-- Auth method response --|                              |
 *   |                           |                              |
 *   |-- [Password auth] ------->| (optional)                   |
 *   |<-- [Auth response] -------|                              |
 *   |                           |                              |
 *   |-- CONNECT host:port ----->|                              |
 *   |                           |-- TLS + CONNECT host:port -->|
 *   |                           |<-- 200 Connection OK --------|
 *   |<-- Success response ------|                              |
 *   |                           |                              |
 *   |<=========== Bidirectional raw byte relay ===============>|
 * </pre>
 * <table border="1">
 *   <tr><th>Feature</th><th>SOCKS4</th><th>SOCKS5</th></tr>
 *   <tr><td>Authentication</td><td>Userid only</td><td>Username/Password, GSSAPI</td></tr>
 *   <tr><td>IPv6</td><td>No</td><td>Yes</td></tr>
 *   <tr><td>Domain names</td><td>SOCKS4a only</td><td>Yes</td></tr>
 *   <tr><td>UDP</td><td>No</td><td>Yes</td></tr>
 * </table>
 *
 * <h2>Supported Commands:</h2>
 * <ul>
 *   <li><b>CONNECT</b> - TCP connection to target (supported)</li>
 *   <li><b>BIND</b> - Not supported</li>
 *   <li><b>UDP ASSOCIATE</b> - Not supported (SOCKS5 only)</li>
 * </ul>
 */
public class Socks5Handler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(Socks5Handler.class);

    private final Config config;
    private final SslContext sslContext;
    private final AccessLog accessLog;

    /**
     * Creates a new SOCKS5 handler.
     *
     * @param config     Proxy configuration
     * @param sslContext SSL context for upstream TLS connections
     * @param accessLog  Access log instance (may be null)
     */
    public Socks5Handler(Config config, SslContext sslContext, AccessLog accessLog) {
        this.config = config;
        this.sslContext = sslContext;
        this.accessLog = accessLog;
    }

    /**
     * Handles incoming SOCKS4 and SOCKS5 messages.
     * Routes to appropriate handler based on message type.
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // SOCKS4 messages
        if (msg instanceof Socks4CommandRequest request) {
            handleSocks4Command(ctx, request);
        }
        // SOCKS5 messages
        else if (msg instanceof Socks5InitialRequest request) {
            handleInitialRequest(ctx, request);
        } else if (msg instanceof Socks5PasswordAuthRequest request) {
            handlePasswordAuth(ctx, request);
        } else if (msg instanceof Socks5CommandRequest request) {
            handleCommandRequest(ctx, request);
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    // ==================== SOCKS4 Handlers ====================

    /**
     * Handles SOCKS4/SOCKS4a CONNECT command.
     * <p>
     * SOCKS4 does not have a separate handshake phase - the command
     * is sent directly. Authentication is userid-based only.
     */
    private void handleSocks4Command(ChannelHandlerContext ctx, Socks4CommandRequest request) {
        // Only CONNECT command is supported
        if (request.type() != Socks4CommandType.CONNECT) {
            log.warn("Unsupported SOCKS4 command: {}", request.type());
            ctx.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.REJECTED_OR_FAILED))
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }

        String targetHost = request.dstAddr();
        int targetPort = request.dstPort();

        // Capture start time and client address for access logging
        long startTime = System.currentTimeMillis();
        String clientAddress = extractClientAddress(ctx);

        log.info("SOCKS4 CONNECT {}:{} via upstream {}:{} (userid: {})",
                targetHost, targetPort, config.upstreamHost(), config.upstreamPort(), request.userId());

        // Connect to upstream proxy via HTTP CONNECT
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(ctx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMillis())
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        // Add SSL handler if upstream requires TLS
                        if (sslContext != null) {
                            p.addLast(sslContext.newHandler(ch.alloc(),
                                    config.upstreamHost(), config.upstreamPort()));
                        }
                        // HTTP codec for CONNECT handshake with upstream
                        p.addLast(new HttpClientCodec());
                        p.addLast(new HttpObjectAggregator(65536));
                        // Handler for upstream CONNECT response (SOCKS4 version)
                        p.addLast(new Socks4ConnectHandler(ctx, targetHost, targetPort, config,
                                accessLog, startTime, clientAddress));
                    }
                });

        bootstrap.connect(config.upstreamHost(), config.upstreamPort())
                .addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        log.error("Failed to connect to upstream for SOCKS4: {}",
                                future.cause().getMessage());
                        ctx.writeAndFlush(new DefaultSocks4CommandResponse(
                                        Socks4CommandStatus.REJECTED_OR_FAILED))
                                .addListener(ChannelFutureListener.CLOSE);
                    }
                });
    }

    // ==================== SOCKS5 Handlers ====================

    /**
     * Handles SOCKS5 initial handshake - authentication method negotiation.
     * <p>
     * The client sends a list of supported authentication methods.
     * We respond with either:
     * <ul>
     *   <li>NO_AUTH (0x00) - if client auth is not required</li>
     *   <li>PASSWORD (0x02) - if client auth is required</li>
     * </ul>
     */
    private void handleInitialRequest(ChannelHandlerContext ctx, Socks5InitialRequest request) {
        log.debug("SOCKS5 initial request from {}: methods={}",
                ctx.channel().remoteAddress(), request.authMethods());

        if (config.requireClientAuth()) {
            // Require username/password authentication (method 0x02)
            // Add decoder for password auth request
            ctx.pipeline().addBefore(ctx.name(), "socks5-password-decoder",
                    new Socks5PasswordAuthRequestDecoder());
            ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.PASSWORD));
        } else {
            // No authentication required (method 0x00)
            // Add decoder for command request
            ctx.pipeline().addBefore(ctx.name(), "socks5-command-decoder",
                    new Socks5CommandRequestDecoder());
            ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH));
        }
    }

    /**
     * Handles SOCKS5 username/password authentication.
     * <p>
     * Validates credentials against configured listen.username and listen.password.
     * On success, adds the command decoder and sends SUCCESS.
     * On failure, sends FAILURE and closes the connection.
     */
    private void handlePasswordAuth(ChannelHandlerContext ctx, Socks5PasswordAuthRequest request) {
        String expectedAuth = config.expectedClientAuthHeader();
        String providedAuth = ConfigLoader.basicHeader(request.username(), request.password());

        if (providedAuth.equals(expectedAuth)) {
            log.debug("SOCKS5 authentication successful for user: {}", request.username());
            // Add command decoder for next phase
            ctx.pipeline().addBefore(ctx.name(), "socks5-command-decoder",
                    new Socks5CommandRequestDecoder());
            ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS));
        } else {
            log.warn("SOCKS5 authentication failed for user: {}", request.username());
            ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE))
                    .addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * Handles SOCKS5 command requests.
     * <p>
     * Only CONNECT command is supported. BIND and UDP ASSOCIATE are rejected.
     * For CONNECT, establishes connection to upstream proxy via HTTP CONNECT.
     */
    private void handleCommandRequest(ChannelHandlerContext ctx, Socks5CommandRequest request) {
        // Only CONNECT command is supported
        if (request.type() != Socks5CommandType.CONNECT) {
            log.warn("Unsupported SOCKS5 command: {}", request.type());
            ctx.writeAndFlush(new DefaultSocks5CommandResponse(
                            Socks5CommandStatus.COMMAND_UNSUPPORTED, Socks5AddressType.IPv4))
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }

        String targetHost = request.dstAddr();
        int targetPort = request.dstPort();

        // Capture start time and client address for access logging
        long startTime = System.currentTimeMillis();
        String clientAddress = extractClientAddress(ctx);

        log.info("SOCKS5 CONNECT {}:{} via upstream {}:{}",
                targetHost, targetPort, config.upstreamHost(), config.upstreamPort());

        // Connect to upstream proxy via HTTP CONNECT
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(ctx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMillis())
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        // Add SSL handler if upstream requires TLS
                        if (sslContext != null) {
                            p.addLast(sslContext.newHandler(ch.alloc(),
                                    config.upstreamHost(), config.upstreamPort()));
                        }
                        // HTTP codec for CONNECT handshake with upstream
                        p.addLast(new HttpClientCodec());
                        p.addLast(new HttpObjectAggregator(65536));
                        // Handler for upstream CONNECT response
                        p.addLast(new Socks5ConnectHandler(ctx, targetHost, targetPort, config,
                                accessLog, startTime, clientAddress));
                    }
                });

        bootstrap.connect(config.upstreamHost(), config.upstreamPort())
                .addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        log.error("Failed to connect to upstream for SOCKS5: {}",
                                future.cause().getMessage());
                        ctx.writeAndFlush(new DefaultSocks5CommandResponse(
                                        Socks5CommandStatus.FAILURE, Socks5AddressType.IPv4))
                                .addListener(ChannelFutureListener.CLOSE);
                    }
                });
    }

    /**
     * Extracts the client IP address from the channel context.
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

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("SOCKS5 handler exception: {}", cause.getMessage());
        ctx.close();
    }
}
