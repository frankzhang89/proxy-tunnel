package xzy.fz.frontend.socks;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import xzy.fz.backend.BackendConnector;
import xzy.fz.config.TunnelConfig;
import xzy.fz.logging.SquidLogger;

public final class SocksServerInitializer extends ChannelInitializer<SocketChannel> {
    private final TunnelConfig config;
    private final BackendConnector connector;
    private final SquidLogger logger;

    public SocksServerInitializer(TunnelConfig config, BackendConnector connector, SquidLogger logger) {
        this.config = config;
        this.connector = connector;
        this.logger = logger;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline().addLast(new Socks5InitialRequestDecoder());
        ch.pipeline().addLast(new Socks5PasswordAuthRequestDecoder());
//        ch.pipeline().addLast(new Socks5PasswordAuthResponseEncoder());
        ch.pipeline().addLast(new SocksServerHandler(config, connector, logger));
    }
}

