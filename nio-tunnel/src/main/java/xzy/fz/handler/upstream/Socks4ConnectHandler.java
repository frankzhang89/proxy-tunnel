package xzy.fz.handler.upstream;

import io.netty.channel.*;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import io.netty.handler.codec.socksx.v4.*;
import xzy.fz.config.Config;
import xzy.fz.handler.HttpProxyHandler;
import xzy.fz.handler.Socks5Handler;
import xzy.fz.log.AccessLog;

/** SOCKS4/4a adaptation for the shared upstream CONNECT flow. */
public class Socks4ConnectHandler extends AbstractUpstreamConnectHandler {
    public Socks4ConnectHandler(ChannelHandlerContext clientCtx, String targetHost, int targetPort,
                                Config config, AccessLog accessLog, long startTime, String clientAddress) {
        super(clientCtx, targetHost, targetPort, config, accessLog, startTime, clientAddress);
    }

    @Override protected Object successResponse(Channel upstream) {
        return new DefaultSocks4CommandResponse(Socks4CommandStatus.SUCCESS);
    }

    @Override protected Object failureResponse(HttpResponseStatus status) {
        return new DefaultSocks4CommandResponse(Socks4CommandStatus.REJECTED_OR_FAILED);
    }

    @Override protected void cleanClientPipeline(ChannelPipeline pipeline) {
        remove(pipeline, Socks4ServerDecoder.class);
        remove(pipeline, Socks4ServerEncoder.class);
        remove(pipeline, SocksPortUnificationServerHandler.class);
        remove(pipeline, Socks5Handler.class);
    }

    @Override protected void logAccess(int status, long bytes) {
        accessLog.logSocks4Connect(clientAddress, HttpProxyHandler.hostPort(targetHost, targetPort), status == 200,
                System.currentTimeMillis() - startTime, bytes);
    }
}
