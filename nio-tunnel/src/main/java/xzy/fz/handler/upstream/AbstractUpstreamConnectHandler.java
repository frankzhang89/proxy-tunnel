package xzy.fz.handler.upstream;

import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xzy.fz.config.Config;
import xzy.fz.handler.HttpProxyHandler;
import xzy.fz.handler.RelayHandler;
import xzy.fz.log.AccessLog;

/** Shared upstream CONNECT handshake and lossless handoff to a raw tunnel. */
abstract class AbstractUpstreamConnectHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
    private static final Logger log = LoggerFactory.getLogger(AbstractUpstreamConnectHandler.class);
    protected final ChannelHandlerContext clientCtx;
    protected final String targetHost;
    protected final int targetPort;
    protected final AccessLog accessLog;
    protected final long startTime;
    protected final String clientAddress;
    private final Config config;
    private boolean established;
    private boolean failed;
    private boolean logged;

    AbstractUpstreamConnectHandler(ChannelHandlerContext clientCtx, String targetHost, int targetPort,
                                  Config config, AccessLog accessLog, long startTime, String clientAddress) {
        this.clientCtx = clientCtx;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.config = config;
        this.accessLog = accessLog;
        this.startTime = startTime;
        this.clientAddress = clientAddress;
    }

    protected abstract Object successResponse(Channel upstream);
    protected abstract Object failureResponse(HttpResponseStatus status);
    protected abstract void cleanClientPipeline(ChannelPipeline pipeline);
    protected abstract void logAccess(int status, long bytes);

    @Override public final void channelActive(ChannelHandlerContext ctx) {
        if (!clientCtx.channel().isActive()) { ctx.close(); return; }
        clientCtx.channel().closeFuture().addListener(future -> ctx.close());
        String target = HttpProxyHandler.hostPort(targetHost, targetPort);
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.CONNECT, target);
        request.headers().set(HttpHeaderNames.HOST, target).set("Proxy-Connection", "keep-alive");
        if (config.expectedUpstreamAuthHeader() != null)
            request.headers().set(HttpHeaderNames.PROXY_AUTHORIZATION, config.expectedUpstreamAuthHeader());
        ctx.writeAndFlush(request).addListener(future -> {
            if (!future.isSuccess()) exceptionCaught(ctx, future.cause());
        });
    }

    @Override protected final void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
        if (!response.decoderResult().isSuccess()) {
            exceptionCaught(ctx, response.decoderResult().cause());
            return;
        }
        if (response.status().code() != 200) { fail(ctx, response.status()); return; }
        if (!clientCtx.channel().isActive()) { ctx.close(); return; }
        Object success = successResponse(ctx.channel());
        established = true;
        // Encode and queue the protocol response before any raw bytes, without blocking.
        clientCtx.writeAndFlush(success).addListener(future -> {
            if (!future.isSuccess()) { logOnce(502, 0); closeBoth(ctx); }
        });
        if (!clientCtx.channel().isActive() || !ctx.channel().isActive()) { closeBoth(ctx); return; }
        Channel client = clientCtx.channel();
        Channel upstream = ctx.channel();
        RelayHandler clientRelay = new RelayHandler(upstream);
        RelayHandler upstreamRelay = new RelayHandler(client);
        client.pipeline().addLast("relay", clientRelay);
        upstream.pipeline().addLast("relay", upstreamRelay);
        client.closeFuture().addListener(future ->
                logOnce(200, clientRelay.getBytesTransferred() + upstreamRelay.getBytesTransferred()));
        // Decoder removal can forward buffered bytes: the relays must already exist.
        cleanClientPipeline(client.pipeline());
        remove(upstream.pipeline(), HttpObjectAggregator.class);
        upstream.pipeline().remove(this);
        // Finish the current decode cycle. CONNECT success puts HttpClientCodec in pass-through mode.
        upstream.eventLoop().execute(() -> {
            try {
                remove(upstream.pipeline(), HttpClientCodec.class);
                if (client.isActive() && upstream.isActive()) RelayHandler.activatePair(client, upstream);
                else closeBoth(ctx);
            } catch (Exception failure) {
                log.debug("CONNECT relay handoff failed", failure);
                closeBoth(ctx);
            }
        });
    }

    private void fail(ChannelHandlerContext ctx, HttpResponseStatus status) {
        if (failed) { ctx.close(); return; }
        failed = true;
        logOnce(status.code(), 0);
        if (clientCtx.channel().isActive())
            clientCtx.writeAndFlush(failureResponse(status)).addListener(ChannelFutureListener.CLOSE);
        ctx.close();
    }

    private void closeBoth(ChannelHandlerContext ctx) { ctx.close(); clientCtx.close(); }

    private void logOnce(int status, long bytes) {
        if (!logged) {
            logged = true;
            if (accessLog != null) logAccess(status, bytes);
        }
    }

    protected static void remove(ChannelPipeline pipeline, Class<? extends ChannelHandler> type) {
        if (pipeline.context(type) != null) pipeline.remove(type);
    }

    @Override public final void channelInactive(ChannelHandlerContext ctx) {
        if (!established && clientCtx.channel().isActive()) fail(ctx, HttpResponseStatus.BAD_GATEWAY);
        ctx.fireChannelInactive();
    }

    @Override public final void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("Upstream CONNECT failed", cause);
        if (established) closeBoth(ctx);
        else fail(ctx, HttpResponseStatus.BAD_GATEWAY);
    }
}
