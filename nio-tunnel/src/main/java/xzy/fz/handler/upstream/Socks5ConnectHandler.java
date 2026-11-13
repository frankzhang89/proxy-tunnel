package xzy.fz.handler.upstream;

import io.netty.channel.*;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import io.netty.handler.codec.socksx.v5.*;
import xzy.fz.config.Config;
import xzy.fz.handler.HttpProxyHandler;
import xzy.fz.handler.Socks5Handler;
import xzy.fz.log.AccessLog;
import java.net.InetSocketAddress;

/** SOCKS5 adaptation for the shared upstream CONNECT flow. */
public class Socks5ConnectHandler extends AbstractUpstreamConnectHandler {
    public Socks5ConnectHandler(ChannelHandlerContext clientCtx, String targetHost, int targetPort,
                                Config config, AccessLog accessLog, long startTime, String clientAddress) {
        super(clientCtx, targetHost, targetPort, config, accessLog, startTime, clientAddress);
    }

    @Override protected Object successResponse(Channel upstream) {
        InetSocketAddress bound = (InetSocketAddress) upstream.localAddress();
        return new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.IPv4,
                bound.getAddress().getHostAddress(), bound.getPort());
    }

    @Override protected Object failureResponse(HttpResponseStatus status) {
        return new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, Socks5AddressType.IPv4);
    }

    @Override protected void cleanClientPipeline(ChannelPipeline pipeline) {
        remove(pipeline, Socks5CommandRequestDecoder.class);
        remove(pipeline, Socks5PasswordAuthRequestDecoder.class);
        remove(pipeline, Socks5InitialRequestDecoder.class);
        remove(pipeline, Socks5ServerEncoder.class);
        remove(pipeline, SocksPortUnificationServerHandler.class);
        remove(pipeline, Socks5Handler.class);
    }

    @Override protected void logAccess(int status, long bytes) {
        accessLog.logSocks5Connect(clientAddress, HttpProxyHandler.hostPort(targetHost, targetPort), status == 200,
                System.currentTimeMillis() - startTime, bytes);
    }
}
