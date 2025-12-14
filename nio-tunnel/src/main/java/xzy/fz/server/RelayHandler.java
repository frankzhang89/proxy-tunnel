package xzy.fz.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;

/**
 * Pipes raw bytes from one channel to another after a tunnel is established.
 */
public final class RelayHandler extends ChannelInboundHandlerAdapter {
    private final Channel peer;

    public RelayHandler(Channel peer) {
        this.peer = peer;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.read();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (peer.isActive()) {
            peer.writeAndFlush(msg).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        } else {
            ReferenceCountUtil.release(msg);
        }
        ctx.read();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (peer.isActive()) {
            peer.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
        if (peer.isActive()) {
            peer.close();
        }
    }
}
