package xzy.fz.server;

import io.netty.channel.Channel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.util.AttributeKey;

/**
 * Captures metadata about a proxied request for logging and back-end decisions.
 */
public final class RequestContext {
    private static final AttributeKey<RequestContext> ATTR = AttributeKey.valueOf("nio-tunnel-context");

    private final String clientIp;
    private final String method;
    private final String uri;
    private final FullHttpRequest fullHttpRequest;
    private final int targetPort;
    private final String targetHost;

    private volatile long bytesTransferred;
    private volatile int responseStatus = 200;

    private RequestContext(String clientIp,
                           String method,
                           String uri,
                           FullHttpRequest request,
                           String targetHost,
                           int targetPort) {
        this.clientIp = clientIp;
        this.method = method;
        this.uri = uri;
        this.fullHttpRequest = request;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    public static RequestContext fromHttpRequest(Channel channel, FullHttpRequest request) {
        String clientIp = clientIp(channel);
        String hostHeader = request.headers().get(HttpHeaderNames.HOST, "");
        int port = 80;
        String host = hostHeader;
        if (hostHeader != null && hostHeader.contains(":")) {
            String[] parts = hostHeader.split(":" , 2);
            host = parts[0];
            port = Integer.parseInt(parts[1]);
        }
        RequestContext context = new RequestContext(clientIp, request.method().name(), request.uri(), request, host, port);
        channel.attr(ATTR).set(context);
        return context;
    }

    public static String clientIp(Channel channel) {
        if (!(channel instanceof SocketChannel socket)) {
            return "0.0.0.0";
        }
        return socket.remoteAddress().getAddress().getHostAddress();
    }

    public FullHttpRequest fullHttpRequest() {
        return fullHttpRequest;
    }

    public String clientIp() {
        return clientIp;
    }

    public String method() {
        return method;
    }

    public String uri() {
        return uri;
    }

    public String targetHost() {
        return targetHost;
    }

    public int targetPort() {
        return targetPort;
    }

    public void addBytes(long bytes) {
        bytesTransferred += bytes;
    }

    public long bytesTransferred() {
        return bytesTransferred;
    }

    public void responseStatus(int status) {
        this.responseStatus = status;
    }

    public int responseStatus() {
        return responseStatus;
    }
}

