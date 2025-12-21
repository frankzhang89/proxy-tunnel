package xzy.fz.handler;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.handler.ssl.SslContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xzy.fz.config.Config;
import xzy.fz.config.ConfigLoader;
import xzy.fz.handler.upstream.Socks5ConnectHandler;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Netty handler for SOCKS5 proxy protocol.
 * <p>
 * This handler implements the SOCKS5 protocol (RFC 1928) and forwards
 * connections to the upstream HTTPS proxy using HTTP CONNECT.
 *
 * <h2>SOCKS5 Protocol Flow:</h2>
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
 *
 * <h2>Supported SOCKS5 Commands:</h2>
 * <ul>
 *   <li><b>CONNECT</b> - TCP connection to target (supported)</li>
 *   <li><b>BIND</b> - Not supported</li>
 *   <li><b>UDP ASSOCIATE</b> - Not supported</li>
 * </ul>
 */
public class Socks5Handler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(Socks5Handler.class);

    private final Config config;
    private final SslContext sslContext;

    /**
     * Creates a new SOCKS5 handler.
     *
     * @param config     Proxy configuration
     * @param sslContext SSL context for upstream TLS connections
     */
    public Socks5Handler(Config config, SslContext sslContext) {
        this.config = config;
        this.sslContext = sslContext;
    }

    /**
     * Handles incoming SOCKS5 messages.
     * Routes to appropriate handler based on message type.
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof Socks5InitialRequest request) {
            handleInitialRequest(ctx, request);
        } else if (msg instanceof Socks5PasswordAuthRequest request) {
            handlePasswordAuth(ctx, request);
        } else if (msg instanceof Socks5CommandRequest request) {
            handleCommandRequest(ctx, request);
        } else {
            ctx.fireChannelRead(msg);
        }
    }

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
                        p.addLast(new Socks5ConnectHandler(ctx, targetHost, targetPort, config));
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

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("SOCKS5 handler exception: {}", cause.getMessage());
        ctx.close();
    }
}
