package xzy.fz;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xzy.fz.config.Cli;
import xzy.fz.config.Config;
import xzy.fz.config.ConfigLoader;
import xzy.fz.handler.HttpProxyHandler;
import xzy.fz.handler.Socks5Handler;

import javax.net.ssl.SSLException;
import java.util.concurrent.TimeUnit;

/**
 * NIO-based proxy tunnel using Netty.
 * <p>
 * This application provides a local HTTP and SOCKS5 proxy that forwards all traffic
 * to an upstream HTTPS proxy. It's designed for environments where direct HTTPS proxy
 * configuration is not supported (e.g., JetBrains IDEs).
 *
 * <h2>Features:</h2>
 * <ul>
 *   <li><b>HTTP Proxy</b> - Handles CONNECT (tunneling) and regular HTTP requests</li>
 *   <li><b>SOCKS5 Proxy</b> - Full SOCKS5 protocol support with optional authentication</li>
 *   <li><b>PAC File</b> - Auto-generates or serves custom Proxy Auto-Config files</li>
 *   <li><b>TLS</b> - Secure communication with upstream HTTPS proxy</li>
 * </ul>
 *
 * <h2>Configuration:</h2>
 * Configuration is loaded from multiple sources (later overrides earlier):
 * <ol>
 *   <li>Classpath resource /config.properties (defaults)</li>
 *   <li>Working directory config.properties</li>
 *   <li>CLI-specified config file (--config=path)</li>
 *   <li>CLI argument overrides (--key=value)</li>
 * </ol>
 *
 * <h2>Architecture:</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                      NIOProxyTunnel                          │
 * ├─────────────────────────────────────────────────────────────┤
 * │  ┌─────────────────┐         ┌─────────────────┐           │
 * │  │  HTTP Listener  │         │ SOCKS5 Listener │           │
 * │  │   (port 8383)   │         │   (port 1080)   │           │
 * │  └────────┬────────┘         └────────┬────────┘           │
 * │           │                           │                     │
 * │  ┌────────▼────────┐         ┌────────▼────────┐           │
 * │  │HttpProxyHandler │         │  Socks5Handler  │           │
 * │  └────────┬────────┘         └────────┬────────┘           │
 * │           │                           │                     │
 * │           └───────────┬───────────────┘                     │
 * │                       │                                     │
 * │              ┌────────▼────────┐                            │
 * │              │  Upstream HTTPS  │                           │
 * │              │   Proxy (TLS)    │                           │
 * │              └──────────────────┘                           │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * @see Config
 * @see HttpProxyHandler
 * @see Socks5Handler
 */
public final class NIOProxyTunnel {
    private static final Logger log = LoggerFactory.getLogger(NIOProxyTunnel.class);

    private NIOProxyTunnel() {
        // Main class - prevent instantiation
    }

    /**
     * Application entry point.
     *
     * @param args Command-line arguments (see Cli.printHelp())
     */
    public static void main(String[] args) {
        // Parse CLI arguments
        Cli cli = Cli.parse(args);
        if (cli.helpRequested()) {
            Cli.printHelp();
            return;
        }

        // Load configuration with layered overrides
        Config config = ConfigLoader.load(cli);

        // Validate required configuration
        if (config.upstreamHost().isBlank()) {
            System.err.println("Missing required property upstream.host (set it in config.properties or via CLI)");
            System.exit(2);
        }

        log.info("Starting nio-tunnel with config: {}", config);

        // Create and start the tunnel server
        TunnelServer server = new TunnelServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "nio-tunnel-shutdown"));
        server.start();
    }

    /**
     * Main server class that manages both HTTP and SOCKS5 proxy listeners.
     * <p>
     * Uses Netty's NIO event loop for non-blocking I/O operations.
     * Two event loop groups are used:
     * <ul>
     *   <li><b>Boss group</b> - Accepts incoming connections (single thread)</li>
     *   <li><b>Worker group</b> - Handles I/O on established connections (multiple threads)</li>
     * </ul>
     */
    private static final class TunnelServer {
        private final Config config;

        /** Boss group handles incoming connections (single thread is sufficient) */
        private final EventLoopGroup bossGroup;

        /** Worker group handles I/O operations (defaults to CPU cores * 2 threads) */
        private final EventLoopGroup workerGroup;

        /** SSL context for upstream HTTPS connections */
        private final SslContext sslContext;

        /**
         * Creates a new tunnel server with the given configuration.
         *
         * @param config Proxy configuration
         */
        TunnelServer(Config config) {
            this.config = config;
            this.bossGroup = new NioEventLoopGroup(1);
            this.workerGroup = new NioEventLoopGroup();

            // Build SSL context for upstream HTTPS proxy connections
            try {
                this.sslContext = config.upstreamTls()
                        ? SslContextBuilder.forClient().build()
                        : null;
            } catch (SSLException e) {
                throw new RuntimeException("Failed to create SSL context", e);
            }
        }

        /**
         * Starts the HTTP and SOCKS5 proxy servers.
         * This method blocks until the server is interrupted.
         */
        void start() {
            try {
                // Start HTTP proxy server
                startHttpProxy();
                // Start SOCKS5 proxy server
                startSocksProxy();

                log.info("HTTP proxy listening on {}:{}", config.listenHost(), config.listenPort());
                log.info("SOCKS5 proxy listening on {}:{}", config.listenHost(), config.socksPort());

                if (config.pacEnabled()) {
                    log.info("PAC file available at http://{}:{}{}",
                            config.pacHost(), config.listenPort(), config.pacPath());
                }

                // Keep the main thread alive
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Server interrupted");
            }
        }

        /**
         * Starts the HTTP proxy server.
         * <p>
         * This server handles:
         * <ul>
         *   <li>HTTP CONNECT requests for HTTPS tunneling</li>
         *   <li>Regular HTTP requests (GET, POST, etc.)</li>
         *   <li>PAC file serving (if enabled)</li>
         * </ul>
         */
        private void startHttpProxy() {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    // Use NIO for non-blocking I/O
                    .channel(NioServerSocketChannel.class)
                    // TCP optimization: disable Nagle's algorithm for lower latency
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    // Enable TCP keepalive to detect dead connections
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();

                            // Idle state handler - closes connections after 120s of inactivity
                            p.addLast("idle", new IdleStateHandler(0, 0, 120, TimeUnit.SECONDS));

                            // HTTP request decoder - converts bytes to HttpRequest objects
                            p.addLast("http-decoder", new HttpRequestDecoder(
                                    config.httpMaxInitialBytes(),  // max initial line length
                                    config.httpMaxInitialBytes(),  // max header size
                                    config.httpMaxInitialBytes()   // max chunk size
                            ));

                            // HTTP response encoder - converts HttpResponse to bytes
                            p.addLast("http-encoder", new HttpResponseEncoder());

                            // Our custom HTTP proxy handler
                            p.addLast("http-proxy-handler", new HttpProxyHandler(config, sslContext));
                        }
                    });

            // Bind and start accepting connections (non-blocking)
            bootstrap.bind(config.listenHost(), config.listenPort()).syncUninterruptibly();
        }

        /**
         * Starts the SOCKS5 proxy server.
         * <p>
         * Supports SOCKS5 protocol with optional username/password authentication.
         * Only CONNECT command is supported (BIND and UDP ASSOCIATE are not).
         */
        private void startSocksProxy() {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();

                            // Idle state handler
                            p.addLast("idle", new IdleStateHandler(0, 0, 120, TimeUnit.SECONDS));

                            // Netty's SOCKS protocol auto-detection and decoding
                            // Automatically handles SOCKS4/SOCKS5 version detection
                            p.addLast("socks-unification", new SocksPortUnificationServerHandler());

                            // Our custom SOCKS5 handler
                            p.addLast("socks5-handler", new Socks5Handler(config, sslContext));
                        }
                    });

            // Bind and start accepting connections
            bootstrap.bind(config.listenHost(), config.socksPort()).syncUninterruptibly();
        }

        /**
         * Gracefully shuts down the server.
         * Closes all event loop groups and waits for termination.
         */
        void shutdown() {
            log.info("Shutting down nio-tunnel...");
            // Graceful shutdown with timeout (0 quiet period, 5 second timeout)
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }
}
