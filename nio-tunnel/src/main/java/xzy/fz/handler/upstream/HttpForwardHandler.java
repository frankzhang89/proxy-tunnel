package xzy.fz.handler.upstream;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import xzy.fz.config.Config;
import xzy.fz.log.AccessLog;

/** Streams an HTTP exchange through the configured upstream proxy. */
public class HttpForwardHandler extends StreamingHttpForwardHandler {
    private final Config config;

    public HttpForwardHandler(ChannelHandlerContext clientCtx, HttpRequest request, Config config,
                              AccessLog accessLog, long startTime, String clientAddress) {
        super(clientCtx, request, accessLog, startTime, clientAddress);
        this.config = config;
    }

    @Override
    protected HttpRequest forwardRequest() {
        HttpRequest request = new DefaultHttpRequest(originalRequest.protocolVersion(),
                originalRequest.method(), originalRequest.uri());
        request.headers().set(originalRequest.headers());
        request.headers().remove(HttpHeaderNames.PROXY_AUTHORIZATION);
        if (config.expectedUpstreamAuthHeader() != null) {
            request.headers().set(HttpHeaderNames.PROXY_AUTHORIZATION, config.expectedUpstreamAuthHeader());
        }
        request.headers().set("Proxy-Connection", "keep-alive");
        return request;
    }
}
