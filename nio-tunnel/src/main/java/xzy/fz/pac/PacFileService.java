package xzy.fz.pac;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.ChunkedWriteHandler;
import xzy.fz.config.TunnelConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Tiny Netty HTTP server that serves either a user-provided PAC file or an auto-generated one
 * pointing IDEs back to the local proxy listener.
 */
public final class PacFileService {
    private final TunnelConfig config;
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final byte[] pacBytes;
    private volatile ChannelFuture serverChannel;

    public PacFileService(TunnelConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.pacBytes = loadPacBytes(config);
    }

    public void start() {
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.SO_REUSEADDR, true)
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(1024));
                        ch.pipeline().addLast(new ChunkedWriteHandler());
                        ch.pipeline().addLast(new PacHandler());
                    }
                });
        serverChannel = bootstrap.bind(config.pacHost(), config.pacPort()).syncUninterruptibly();
    }

    public void stop() {
        if (serverChannel != null) {
            serverChannel.channel().close().syncUninterruptibly();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    private final class PacHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(io.netty.channel.ChannelHandlerContext ctx, FullHttpRequest request) {
            if (!request.uri().equals(config.pacPath())) {
                FullHttpResponse notFound = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
                ctx.writeAndFlush(notFound).addListener(f -> ctx.close());
                return;
            }
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                    Unpooled.wrappedBuffer(pacBytes));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/x-ns-proxy-autoconfig; charset=utf-8");
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, pacBytes.length);
            ctx.writeAndFlush(response).addListener(f -> ctx.close());
        }
    }

    private static byte[] loadPacBytes(TunnelConfig config) {
        if (config.pacFile() != null && !config.pacFile().isBlank()) {
            Path path = Path.of(config.pacFile());
            try {
                return Files.readAllBytes(path);
            } catch (IOException ioe) {
                throw new IllegalStateException("Unable to read PAC file " + path, ioe);
            }
        }
        String pac = "function FindProxyForURL(url, host) {\n" +
                "    return \"PROXY " + config.listenHost() + ":" + config.listenPort() + "; DIRECT\";\n" +
                "}";
        return pac.getBytes(StandardCharsets.UTF_8);
    }
}

