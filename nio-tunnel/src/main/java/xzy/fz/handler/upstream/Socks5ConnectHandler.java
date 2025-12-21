package xzy.fz.handler.upstream;

import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import io.netty.handler.codec.socksx.v5.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xzy.fz.config.Config;
import xzy.fz.handler.RelayHandler;
import xzy.fz.handler.Socks5Handler;

import java.net.InetSocketAddress;

/**
 * Handles upstream proxy response for SOCKS5 CONNECT requests.
 * <p>
 * This handler is used when a SOCKS5 client sends a CONNECT command.
 * It sends an HTTP CONNECT to the upstream proxy and translates the
 * response back to SOCKS5 protocol.
 *
 * <h2>Flow:</h2>
 * <ol>
 *   <li>On channel activation, sends HTTP CONNECT to upstream</li>
 *   <li>Receives 200 response from upstream</li>
 *   <li>Sends SOCKS5 success response to client</li>
 *   <li>Switches both channels to relay mode</li>
 * </ol>
 */
public class Socks5ConnectHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
    private static final Logger log = LoggerFactory.getLogger(Socks5ConnectHandler.class);

    private final ChannelHandlerContext clientCtx;
    private final String targetHost;
    private final int targetPort;
    private final Config config;

    /**
     * Creates a new SOCKS5 upstream connect handler.
     *
     * @param clientCtx  Client channel context
     * @param targetHost Target host from SOCKS5 CONNECT command
     * @param targetPort Target port from SOCKS5 CONNECT command
     * @param config     Proxy configuration
     */
    public Socks5ConnectHandler(ChannelHandlerContext clientCtx, String targetHost,
                                int targetPort, Config config) {
        this.clientCtx = clientCtx;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.config = config;
    }

    /**
     * Called when upstream connection is established.
     * Sends HTTP CONNECT request to upstream proxy.
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // Build HTTP CONNECT request
        FullHttpRequest connectRequest = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.CONNECT, targetHost + ":" + targetPort);
        connectRequest.headers()
                .set(HttpHeaderNames.HOST, targetHost + ":" + targetPort)
                .set("Proxy-Connection", "keep-alive");

        // Add upstream proxy authentication if configured
        if (config.expectedUpstreamAuthHeader() != null) {
            connectRequest.headers().set(HttpHeaderNames.PROXY_AUTHORIZATION,
                    config.expectedUpstreamAuthHeader());
        }

        log.debug("SOCKS5: Sending HTTP CONNECT to upstream for {}:{}", targetHost, targetPort);
        ctx.writeAndFlush(connectRequest);
    }

    /**
     * Handles upstream proxy's response to CONNECT request.
     */
    @Override
    protected void channelRead0(ChannelHandlerContext upstreamCtx, FullHttpResponse response) {
        if (response.status().code() == 200) {
            log.debug("SOCKS5 upstream CONNECT successful for {}:{}", targetHost, targetPort);

            // Send SOCKS5 success response to client
            // Use local address as the bound address (required by SOCKS5 protocol)
            InetSocketAddress upstreamAddr = (InetSocketAddress) upstreamCtx.channel().localAddress();
            clientCtx.writeAndFlush(new DefaultSocks5CommandResponse(
                    Socks5CommandStatus.SUCCESS,
                    Socks5AddressType.IPv4,
                    upstreamAddr.getAddress().getHostAddress(),
                    upstreamAddr.getPort()));

            // Switch both channels to relay mode
            switchToRelayMode(upstreamCtx);
        } else {
            log.error("SOCKS5 upstream CONNECT failed: {}", response.status());

            // Send SOCKS5 failure response to client
            clientCtx.writeAndFlush(new DefaultSocks5CommandResponse(
                            Socks5CommandStatus.FAILURE, Socks5AddressType.IPv4))
                    .addListener(ChannelFutureListener.CLOSE);
            upstreamCtx.close();
        }
    }

    /**
     * Transforms both pipelines for raw byte relay.
     */
    private void switchToRelayMode(ChannelHandlerContext upstreamCtx) {
        Channel clientChannel = clientCtx.channel();
        Channel upstreamChannel = upstreamCtx.channel();

        // Remove SOCKS5 codecs from client pipeline
        removeSocks5Handlers(clientChannel.pipeline());
        removeHandlerSafely(clientChannel.pipeline(), Socks5Handler.class);

        // Remove HTTP codecs from upstream pipeline
        removeHandlerSafely(upstreamChannel.pipeline(), HttpClientCodec.class);
        removeHandlerSafely(upstreamChannel.pipeline(), HttpObjectAggregator.class);
        upstreamChannel.pipeline().remove(this);

        // Add relay handlers for bidirectional byte forwarding
        clientChannel.pipeline().addLast("relay", new RelayHandler(upstreamChannel));
        upstreamChannel.pipeline().addLast("relay", new RelayHandler(clientChannel));

        log.debug("SOCKS5: Switched to relay mode for {}:{}", targetHost, targetPort);
    }

    /**
     * Removes all SOCKS5 related handlers from pipeline.
     */
    private void removeSocks5Handlers(ChannelPipeline pipeline) {
        removeHandlerSafely(pipeline, SocksPortUnificationServerHandler.class);
        removeHandlerSafely(pipeline, Socks5CommandRequestDecoder.class);
        removeHandlerSafely(pipeline, Socks5PasswordAuthRequestDecoder.class);
        removeHandlerSafely(pipeline, Socks5InitialRequestDecoder.class);
    }

    /**
     * Safely removes a handler from the pipeline.
     */
    private void removeHandlerSafely(ChannelPipeline pipeline, Class<? extends ChannelHandler> handlerType) {
        try {
            pipeline.remove(handlerType);
        } catch (Exception ignored) {
            // Handler may not exist in pipeline
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("SOCKS5 upstream error: {}", cause.getMessage());

        // Send SOCKS5 failure to client
        if (clientCtx.channel().isActive()) {
            clientCtx.writeAndFlush(new DefaultSocks5CommandResponse(
                            Socks5CommandStatus.FAILURE, Socks5AddressType.IPv4))
                    .addListener(ChannelFutureListener.CLOSE);
        }
        ctx.close();
    }
}
