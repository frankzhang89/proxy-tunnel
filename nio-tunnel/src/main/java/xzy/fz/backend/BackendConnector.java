package xzy.fz.backend;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import xzy.fz.config.TunnelConfig;
import xzy.fz.frontend.socks.SocksServerHandler;
import xzy.fz.logging.SquidLogger;
import xzy.fz.server.RelayHandler;
import xzy.fz.server.RequestContext;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;

/**
 * Establishes outbound connections to upstream proxies or destination hosts.
 */
public final class BackendConnector {
    private final TunnelConfig config;
    private final EventLoopGroup group;
    private final SquidLogger logger;
    private final SslContext sslContext;

    public BackendConnector(TunnelConfig config, EventLoopGroup group, SquidLogger logger) {
        this.config = config;
        this.group = group;
        this.logger = logger;
        this.sslContext = buildSslContext(config);
    }

    public void connectHttp(RequestContext context, Channel inbound) {
        FullHttpRequest request = context.fullHttpRequest();

        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.AUTO_READ, false)
                .handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new LoggingHandler(LogLevel.INFO));
                        if (config.upstreamTls()) {
                            pipeline.addLast(sslContext.newHandler(ch.alloc(), config.upstreamHost(), config.upstreamPort()));
                        }
                        if (config.upstreamAuthEnabled()) {
                            pipeline.addLast(new HttpClientCodec());
                            pipeline.addLast(new HttpObjectAggregator(1048576));
                        }
                        pipeline.addLast(new ProxyBackendHandler(inbound, context, logger));
                    }
                });

        ChannelFuture connectFuture = bootstrap.connect(new InetSocketAddress(config.upstreamHost(), config.upstreamPort()));
        connectFuture.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                future.channel().writeAndFlush(request).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                future.channel().read();
            } else {
                fail(context, inbound);
            }
        });
    }

    public void connectSocks(RequestContext context, Channel inbound, Socks5CommandRequest cmd) {
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.AUTO_READ, false)
                .handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new RelayHandler(inbound));
                    }
                });

        bootstrap.connect(new InetSocketAddress(cmd.dstAddr(), cmd.dstPort())).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                Channel outbound = future.channel();
                installRelay(inbound, outbound);
                DefaultSocks5CommandResponse success = new DefaultSocks5CommandResponse(
                        Socks5CommandStatus.SUCCESS, cmd.dstAddrType(), cmd.dstAddr(), cmd.dstPort());
                inbound.writeAndFlush(success).addListener(writeFuture -> {
                    if (writeFuture.isSuccess()) {
                        inbound.read();
                        outbound.read();
                    } else {
                        closeQuietly(outbound);
                    }
                });
            } else {
                DefaultSocks5CommandResponse failure = new DefaultSocks5CommandResponse(
                        Socks5CommandStatus.FAILURE, cmd.dstAddrType());
                inbound.writeAndFlush(failure).addListener(ChannelFutureListener.CLOSE);
                logger.log(context.clientIp(), context.method(), context.uri(), 503, 0);
            }
        });
    }

    private void installRelay(Channel inbound, Channel outbound) {
        ChannelHandlerContext ctx = inbound.pipeline().context(SocksServerHandler.class);
        if (ctx != null) {
            ctx.pipeline().replace(ctx.handler(), "socks-relay", new RelayHandler(outbound));
        } else {
            inbound.pipeline().addLast(new RelayHandler(outbound));
        }
    }

    private static void closeQuietly(Channel channel) {
        if (channel != null && channel.isActive()) {
            channel.close();
        }
    }

    private void fail(RequestContext context, Channel inbound) {
        logger.log(context.clientIp(), context.method(), context.uri(), 503, 0);
        inbound.close();
    }

    private static SslContext buildSslContext(TunnelConfig config) {
        try {
            if (!config.upstreamTls()) {
                return null;
            }
            return SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
        } catch (SSLException e) {
            throw new IllegalStateException("Failed to build SSL context", e);
        }
    }
}
