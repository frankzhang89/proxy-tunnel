package xzy.fz.handler.upstream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import xzy.fz.config.Config;
import xzy.fz.handler.RelayHandler;
import xzy.fz.util.NoProxyMatcher;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpUpgradeRelayTest {
    private EmbeddedChannel clientChannel;
    private EmbeddedChannel serverChannel;

    @AfterEach
    void releaseChannels() {
        if (clientChannel != null) {
            clientChannel.finishAndReleaseAll();
        }
        if (serverChannel != null) {
            serverChannel.finishAndReleaseAll();
        }
    }

    @Test
    void relaysWebSocketFramesAfterUpgradeThroughUpstreamProxy() {
        HttpRequest request = websocketRequest("ws://example.test/socket");
        ChannelHandlerContext clientCtx = newClientContext();

        serverChannel = newServerChannel(new HttpForwardHandler(
                clientCtx, request, testConfig(), null, System.currentTimeMillis(), "127.0.0.1"));
        releaseAllOutbound(serverChannel); // forwarded HTTP Upgrade request

        assertUpgradeAndRawRelay();
    }

    @Test
    void relaysWebSocketFramesAfterDirectUpgrade() {
        HttpRequest request = websocketRequest("ws://example.test/socket");
        ChannelHandlerContext clientCtx = newClientContext();

        serverChannel = newServerChannel(new DirectHttpForwardHandler(
                clientCtx, request, "/socket", null, System.currentTimeMillis(), "127.0.0.1"));
        releaseAllOutbound(serverChannel); // forwarded HTTP Upgrade request

        assertUpgradeAndRawRelay();
    }

    private ChannelHandlerContext newClientContext() {
        clientChannel = new EmbeddedChannel();
        clientChannel.pipeline().addLast("http-decoder", new HttpRequestDecoder());
        clientChannel.pipeline().addLast("http-encoder", new HttpResponseEncoder());
        clientChannel.pipeline().addLast("http-proxy-handler", new ChannelInboundHandlerAdapter());
        return clientChannel.pipeline().context("http-proxy-handler");
    }

    private void assertUpgradeAndRawRelay() {
        byte[] firstServerFrame = new byte[]{(byte) 0x81, 0x02, 'h', 'i'};
        ByteBuf response = Unpooled.buffer();
        response.writeCharSequence(
                "HTTP/1.1 101 Switching Protocols\r\n" +
                "Connection: Upgrade\r\n" +
                "Upgrade: websocket\r\n" +
                "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n",
                StandardCharsets.US_ASCII);
        response.writeBytes(firstServerFrame);
        serverChannel.writeInbound(response);

        ByteBuf encodedResponse = clientChannel.readOutbound();
        assertNotNull(encodedResponse);
        assertTrue(encodedResponse.toString(StandardCharsets.US_ASCII)
                .startsWith("HTTP/1.1 101 Switching Protocols"));
        encodedResponse.release();

        assertNull(clientChannel.pipeline().context(HttpRequestDecoder.class));
        assertNull(clientChannel.pipeline().context(HttpResponseEncoder.class));
        assertNotNull(clientChannel.pipeline().context(RelayHandler.class));
        assertNotNull(serverChannel.pipeline().context(RelayHandler.class));
        // Streaming headers and LastHttpContent are separate writes. The HTTP
        // encoder can emit an empty buffer for the latter (zero bytes on TCP).
        assertBufferEquals(firstServerFrame, readNonEmptyOutbound(clientChannel));

        ByteBuf serverFrame = Unpooled.wrappedBuffer(new byte[]{(byte) 0x81, 0x02, 'o', 'k'});
        serverChannel.writeInbound(serverFrame);
        assertBufferEquals(new byte[]{(byte) 0x81, 0x02, 'o', 'k'}, clientChannel.readOutbound());

        ByteBuf clientFrame = Unpooled.wrappedBuffer(new byte[]{(byte) 0x89, 0x00});
        clientChannel.writeInbound(clientFrame);
        assertBufferEquals(new byte[]{(byte) 0x89, 0x00}, serverChannel.readOutbound());
    }

    private static EmbeddedChannel newServerChannel(ChannelHandler handler) {
        return new EmbeddedChannel(
                new HttpClientCodec(),
                handler);
    }

    private static HttpRequest websocketRequest(String uri) {
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
        request.headers()
                .set(HttpHeaderNames.HOST, "example.test")
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE)
                .set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET)
                .set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13")
                .set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");
        return request;
    }

    private static Config testConfig() {
        return new Config(
                "127.0.0.1", 8380, 1080,
                false, null,
                "proxy.test", 443, true, null,
                5000, 1048576,
                false, "/proxy.pac", "127.0.0.1", null,
                "nio-tunnel", null, null,
                false, false,
                new NoProxyMatcher(""));
    }

    private static void assertBufferEquals(byte[] expected, Object actual) {
        ByteBuf buffer = (ByteBuf) actual;
        try {
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            assertArrayEquals(expected, bytes);
        } finally {
            buffer.release();
        }
    }

    private static ByteBuf readNonEmptyOutbound(EmbeddedChannel channel) {
        ByteBuf buffer;
        while ((buffer = channel.readOutbound()) != null) {
            if (buffer.isReadable()) return buffer;
            buffer.release();
        }
        throw new AssertionError("Expected outbound bytes");
    }

    private static void releaseAllOutbound(EmbeddedChannel channel) {
        Object message;
        while ((message = channel.readOutbound()) != null) {
            ReferenceCountUtil.release(message);
        }
    }
}
