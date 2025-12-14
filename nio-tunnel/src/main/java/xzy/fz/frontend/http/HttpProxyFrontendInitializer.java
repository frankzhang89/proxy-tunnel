package xzy.fz.frontend.http;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import xzy.fz.backend.BackendConnector;
import xzy.fz.config.TunnelConfig;
import xzy.fz.logging.SquidLogger;
import xzy.fz.server.ClientAuthenticator;

public final class HttpProxyFrontendInitializer extends ChannelInitializer<SocketChannel> {
    private final TunnelConfig config;
    private final BackendConnector connector;
    private final SquidLogger logger;

    public HttpProxyFrontendInitializer(TunnelConfig config, BackendConnector connector, SquidLogger logger) {
        this.config = config;
        this.connector = connector;
        this.logger = logger;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast(new LoggingHandler(LogLevel.INFO));
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(config.httpMaxInitialBytes()));
        pipeline.addLast(new HttpServerExpectContinueHandler());
        pipeline.addLast(new ProxyFrontendHandler(config, connector, logger, ClientAuthenticator.basic(config)));
    }
}
