package xzy.fz.frontend.socks;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.v5.*;
import xzy.fz.backend.BackendConnector;
import xzy.fz.config.TunnelConfig;
import xzy.fz.logging.SquidLogger;
import xzy.fz.server.RequestContext;

public final class SocksServerHandler extends SimpleChannelInboundHandler<Socks5Message> {
    private final TunnelConfig config;
    private final BackendConnector connector;
    private final SquidLogger logger;

    public SocksServerHandler(TunnelConfig config, BackendConnector connector, SquidLogger logger) {
        super(false);
        this.config = config;
        this.connector = connector;
        this.logger = logger;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Socks5Message msg) {
        if (msg instanceof Socks5InitialRequest initialRequest) {
            ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH));
            return;
        }
        if (msg instanceof Socks5CommandRequest cmdRequest) {
            handleCommand(ctx, cmdRequest);
        }
    }

    private void handleCommand(ChannelHandlerContext ctx, Socks5CommandRequest cmd) {
        if (cmd.type() != Socks5CommandType.CONNECT) {
            ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, cmd.dstAddrType()))
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        RequestContext context = RequestContext.forSocks(ctx.channel(), cmd);
        connector.connectSocks(context, ctx.channel(), cmd);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.log(RequestContext.clientIp(ctx.channel()), "SOCKS", ctx.channel().remoteAddress().toString(), 500, 0);
        ctx.close();
    }
}
