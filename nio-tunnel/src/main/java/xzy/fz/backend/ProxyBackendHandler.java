package xzy.fz.backend;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import xzy.fz.logging.SquidLogger;
import xzy.fz.server.RequestContext;

/**
 * Pumps data between inbound and outbound channels once the upstream connection is established.
 */
public final class ProxyBackendHandler extends ChannelInboundHandlerAdapter {
    private final Channel inbound;
    private final RequestContext context;
    private final SquidLogger logger;

    public ProxyBackendHandler(Channel inbound, RequestContext context, SquidLogger logger) {
        this.inbound = inbound;
        this.context = context;
        this.logger = logger;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        inbound.read();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        inbound.writeAndFlush(msg).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        closeOnFlush(inbound);
        logger.log(context.clientIp(), context.method(), context.uri(), context.responseStatus(), context.bytesTransferred());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        closeOnFlush(ctx.channel());
    }

    private static void closeOnFlush(Channel ch) {
        if (ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
