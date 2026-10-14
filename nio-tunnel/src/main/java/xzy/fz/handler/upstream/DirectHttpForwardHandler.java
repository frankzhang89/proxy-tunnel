package xzy.fz.handler.upstream;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import xzy.fz.log.AccessLog;

/** Streams an HTTP exchange directly to its origin, using an origin-form URI. */
public class DirectHttpForwardHandler extends StreamingHttpForwardHandler {
    private final String relativeUri;

    public DirectHttpForwardHandler(ChannelHandlerContext clientCtx, HttpRequest request, String relativeUri,
                                    AccessLog accessLog, long startTime, String clientAddress) {
        super(clientCtx, request, accessLog, startTime, clientAddress);
        this.relativeUri = relativeUri;
    }

    @Override
    protected HttpRequest forwardRequest() {
        HttpRequest request = new DefaultHttpRequest(originalRequest.protocolVersion(),
                originalRequest.method(), relativeUri);
        request.headers().set(originalRequest.headers());
        request.headers().remove(HttpHeaderNames.PROXY_AUTHORIZATION);
        request.headers().remove("Proxy-Connection");
        return request;
    }
}
