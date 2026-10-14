package xzy.fz.handler.upstream;

import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import xzy.fz.handler.HttpProxyHandler;
import xzy.fz.handler.RelayHandler;
import xzy.fz.log.AccessLog;

/** One streaming HTTP exchange, shared by direct and upstream proxy routes. */
abstract class StreamingHttpForwardHandler extends ChannelInboundHandlerAdapter {
    protected final HttpRequest originalRequest;
    private final ChannelHandlerContext clientCtx;
    private final AccessLog accessLog;
    private final long startTime;
    private final String clientAddress;
    private boolean informational;
    private boolean upgrade;
    private boolean responseStarted;
    private boolean complete;
    private boolean closeClient;
    private int statusCode;
    private String contentType;
    private long responseBytes;

    StreamingHttpForwardHandler(ChannelHandlerContext clientCtx, HttpRequest request,
                                AccessLog accessLog, long startTime, String clientAddress) {
        this.clientCtx = clientCtx;
        this.originalRequest = request;
        this.accessLog = accessLog;
        this.startTime = startTime;
        this.clientAddress = clientAddress;
    }

    protected abstract HttpRequest forwardRequest();

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        if (!clientCtx.channel().isActive()) {
            ctx.close();
            return;
        }
        // HttpProxyHandler supplies content after these headers, including LastHttpContent.
        ctx.writeAndFlush(forwardRequest()).addListener(future -> {
            if (!future.isSuccess()) exceptionCaught(ctx, future.cause());
        });
        HttpProxyHandler client = clientHandler();
        if (client != null) client.forwardReady(clientCtx, ctx.channel());
        ctx.channel().config().setAutoRead(clientCtx.channel().isWritable());
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof HttpObject object)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (complete || !object.decoderResult().isSuccess()) {
            Throwable cause = object.decoderResult().cause();
            ReferenceCountUtil.release(msg);
            if (!complete) exceptionCaught(ctx, cause);
            return;
        }
        if (msg instanceof HttpResponse response) {
            int code = response.status().code();
            informational = code >= 100 && code < 200 && code != 101;
            upgrade = code == 101 && originalRequest.headers().contains(HttpHeaderNames.UPGRADE);
            HttpResponse headers = new DefaultHttpResponse(response.protocolVersion(), response.status());
            headers.headers().set(response.headers());
            if (!informational) {
                responseStarted = true;
                statusCode = code;
                contentType = response.headers().get(HttpHeaderNames.CONTENT_TYPE);
                HttpProxyHandler client = clientHandler();
                closeClient = !HttpUtil.isKeepAlive(originalRequest) || !HttpUtil.isKeepAlive(response)
                        || (client != null && !client.isRequestComplete());
                if (!upgrade) {
                    boolean hasBody = !originalRequest.method().equals(HttpMethod.HEAD)
                            && code >= 200 && code != 204 && code != 304;
                    if (hasBody && !HttpUtil.isContentLengthSet(headers)
                            && !HttpUtil.isTransferEncodingChunked(headers)) {
                        // An EOF-delimited upstream response needs a downstream boundary.
                        if (originalRequest.protocolVersion().equals(HttpVersion.HTTP_1_1)) {
                            HttpUtil.setTransferEncodingChunked(headers, true);
                        } else {
                            closeClient = true;
                        }
                    }
                    if (originalRequest.protocolVersion().equals(HttpVersion.HTTP_1_0)
                            && HttpUtil.isTransferEncodingChunked(headers)) {
                        headers.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
                        closeClient = true;
                    }
                    HttpUtil.setKeepAlive(headers, !closeClient);
                }
            }
            writeClient(ctx, headers);
        }
        if (msg instanceof HttpContent content) {
            if (!informational) responseBytes += content.content().readableBytes();
            ChannelFuture write = writeClient(ctx, ReferenceCountUtil.retain(content));
            if (content instanceof LastHttpContent && !informational) {
                complete = true;
                logAccess();
                if (upgrade) {
                    switchToRelayMode(ctx);
                } else {
                    write.addListener(future -> {
                        ctx.close();
                        if (!future.isSuccess() || closeClient) {
                            clientCtx.close();
                        } else {
                            HttpProxyHandler client = clientHandler();
                            if (client != null) client.forwardComplete(clientCtx);
                        }
                    });
                }
            }
        }
        ReferenceCountUtil.release(msg);
    }

    private ChannelFuture writeClient(ChannelHandlerContext ctx, Object msg) {
        return clientCtx.writeAndFlush(msg).addListener(future -> {
            if (!future.isSuccess()) {
                ctx.close();
                clientCtx.close();
            }
        });
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        HttpProxyHandler client = clientHandler();
        if (client != null) client.forwardWritabilityChanged(clientCtx);
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (!complete) failClient();
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (!complete) failClient();
        ctx.close();
    }

    private void failClient() {
        complete = true;
        if (!clientCtx.channel().isActive()) return;
        if (responseStarted) {
            // A second response after headers/body would corrupt the stream.
            clientCtx.close();
        } else {
            HttpProxyHandler.sendError(clientCtx, HttpResponseStatus.BAD_GATEWAY, "Bad Gateway");
        }
    }

    private HttpProxyHandler clientHandler() {
        return clientCtx.pipeline().get(HttpProxyHandler.class);
    }

    private void switchToRelayMode(ChannelHandlerContext ctx) {
        Channel client = clientCtx.channel();
        Channel upstream = ctx.channel();
        // Decoders may release buffered bytes when removed: install relays first.
        client.pipeline().addLast("relay", new RelayHandler(upstream));
        upstream.pipeline().addLast("relay", new RelayHandler(client));
        remove(client.pipeline(), HttpRequestDecoder.class);
        remove(client.pipeline(), HttpResponseEncoder.class);
        if (client.pipeline().context("http-proxy-handler") != null) {
            client.pipeline().remove("http-proxy-handler");
        }
        upstream.pipeline().remove(this);
        client.config().setAutoRead(true);
        upstream.config().setAutoRead(true);
        // Preserve frames coalesced with the 101 response in this read cycle.
        upstream.eventLoop().execute(() -> remove(upstream.pipeline(), HttpClientCodec.class));
    }

    private static void remove(ChannelPipeline pipeline, Class<? extends ChannelHandler> type) {
        if (pipeline.context(type) != null) pipeline.remove(type);
    }

    private void logAccess() {
        if (accessLog != null) {
            accessLog.logHttpForward(clientAddress, originalRequest.method().name(), originalRequest.uri(),
                    statusCode, System.currentTimeMillis() - startTime, responseBytes, contentType);
        }
    }
}
