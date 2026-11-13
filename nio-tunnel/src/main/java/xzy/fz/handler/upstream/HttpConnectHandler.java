package xzy.fz.handler.upstream;

import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import xzy.fz.config.Config;
import xzy.fz.handler.HttpProxyHandler;
import xzy.fz.log.AccessLog;

/** HTTP protocol adaptation for the shared upstream CONNECT flow. */
public class HttpConnectHandler extends AbstractUpstreamConnectHandler {
    public HttpConnectHandler(ChannelHandlerContext clientCtx, String targetHost, int targetPort,
                              Config config, AccessLog accessLog, long startTime, String clientAddress) {
        super(clientCtx, targetHost, targetPort, config, accessLog, startTime, clientAddress);
    }

    @Override protected Object successResponse(Channel upstream) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                new HttpResponseStatus(200, "Connection Established"));
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
                .set("Proxy-Connection", "keep-alive");
        return response;
    }

    @Override protected Object failureResponse(HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0)
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        return response;
    }

    @Override protected void cleanClientPipeline(ChannelPipeline pipeline) {
        remove(pipeline, HttpRequestDecoder.class);
        remove(pipeline, HttpResponseEncoder.class);
        remove(pipeline, HttpProxyHandler.class);
    }

    @Override protected void logAccess(int status, long bytes) {
        accessLog.logConnect(clientAddress, HttpProxyHandler.hostPort(targetHost, targetPort), status,
                System.currentTimeMillis() - startTime, bytes);
    }
}
