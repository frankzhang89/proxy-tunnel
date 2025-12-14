package xzy.fz.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import xzy.fz.backend.BackendConnector;
import xzy.fz.config.TunnelConfig;
import xzy.fz.frontend.http.HttpProxyFrontendInitializer;
import xzy.fz.frontend.socks.SocksServerInitializer;
import xzy.fz.logging.SquidLogger;
import xzy.fz.pac.PacFileService;

public final class ProxyApplication {
    private final TunnelConfig config;
    private final SquidLogger logger;
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final EventLoopGroup socksBossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup socksWorkerGroup = new NioEventLoopGroup();
    private volatile ChannelFuture httpServer;
    private volatile ChannelFuture socksServer;
    private PacFileService pacService;

    public ProxyApplication(TunnelConfig config, SquidLogger logger) {
        this.config = config;
        this.logger = logger;
    }

    public void start() {
        BackendConnector connector = new BackendConnector(config, workerGroup, logger);
        ServerBootstrap httpBootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new HttpProxyFrontendInitializer(config, connector, logger))
                .childOption(ChannelOption.AUTO_READ, true);
        httpServer = httpBootstrap.bind(config.listenHost(), config.listenPort()).syncUninterruptibly();

        if (config.socksEnabled()) {
            ServerBootstrap socksBootstrap = new ServerBootstrap()
                    .group(socksBossGroup, socksWorkerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new SocksServerInitializer(config, connector, logger))
                    .childOption(ChannelOption.AUTO_READ, true);
            socksServer = socksBootstrap.bind(config.listenHost(), config.socksPort()).syncUninterruptibly();
        }

        if (config.pacEnabled()) {
            pacService = new PacFileService(config);
            pacService.start();
        }
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.channel().close().syncUninterruptibly();
        }
        if (socksServer != null) {
            socksServer.channel().close().syncUninterruptibly();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        socksBossGroup.shutdownGracefully();
        socksWorkerGroup.shutdownGracefully();
        if (pacService != null) {
            pacService.stop();
        }
        logger.close();
    }
}
