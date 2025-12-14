package xzy.fz.frontend.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import xzy.fz.backend.BackendConnector;
import xzy.fz.config.TunnelConfig;
import xzy.fz.logging.SquidLogger;
import xzy.fz.server.ClientAuthenticator;
import xzy.fz.server.RelayHandler;
import xzy.fz.server.RequestContext;

/**
 * Handles inbound HTTP proxy requests and forwards them to BackendConnector.
 */
public final class ProxyFrontendHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final TunnelConfig config;
    private final BackendConnector connector;
    private final SquidLogger logger;
    private final ClientAuthenticator authenticator;

    public ProxyFrontendHandler(TunnelConfig config,
                                BackendConnector connector,
                                SquidLogger logger,
                                ClientAuthenticator authenticator) {
        super(false);
        this.config = config;
        this.connector = connector;
        this.logger = logger;
        this.authenticator = authenticator;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!authenticator.authorize(request.headers().get(HttpHeaderNames.PROXY_AUTHORIZATION))) {
            sendAuthRequired(ctx);
            ReferenceCountUtil.release(request);
            return;
        }

        if (HttpMethod.CONNECT.equals(request.method())) {
            handleConnect(ctx, request);
            return;
        }

        RequestContext context = RequestContext.fromHttpRequest(ctx.channel(), request);
        connector.connectHttp(context, ctx.channel());
    }

    private void handleConnect(ChannelHandlerContext ctx, FullHttpRequest request) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.EMPTY_BUFFER);
        ctx.writeAndFlush(response).addListener(f -> {
            if (f.isSuccess()) {
                ctx.pipeline().remove(this);
                ctx.pipeline().addLast(new RelayHandler(ctx.channel()));
            } else {
                ctx.close();
            }
        });
        logger.log(RequestContext.clientIp(ctx.channel()), "CONNECT", request.uri(), 200, 0);
    }

    private void sendAuthRequired(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED);
        response.headers().set(HttpHeaderNames.PROXY_AUTHENTICATE, "Basic realm=\"" + config.serverName() + "\"");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.log(RequestContext.clientIp(ctx.channel()), "ERR", ctx.channel().remoteAddress().toString(), 500, 0);
        ctx.close();
    }
}
