package xzy.fz.handler.upstream;

import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.socksx.v4.Socks4ServerEncoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.EnumSource;
import xzy.fz.config.Config;
import xzy.fz.handler.RelayHandler;
import xzy.fz.log.AccessLog;
import xzy.fz.util.NoProxyMatcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class UpstreamConnectRelayTest {
    enum Protocol { HTTP, SOCKS4, SOCKS5 }
    private static final byte[] HEADER = "HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GREETING = {0, (byte) 0xff, 22, 3, 1, 'h', 'i'};

    @ParameterizedTest @EnumSource(Protocol.class)
    void preservesCoalescedAndFragmentedFirstDataExactlyOnce(Protocol protocol) {
        for (int split : new int[]{0, 1, HEADER.length - 1, HEADER.length, HEADER.length + 3}) {
            try (Fixture f = new Fixture(protocol, false)) {
                byte[] packet = new byte[HEADER.length + GREETING.length];
                System.arraycopy(HEADER, 0, packet, 0, HEADER.length);
                System.arraycopy(GREETING, 0, packet, HEADER.length, GREETING.length);
                if (split > 0) f.upstream.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(packet, 0, split)));
                f.upstream.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(packet, split, packet.length)));
                f.upstream.runPendingTasks();
                assertArrayEquals(GREETING, f.stripResponse(drain(f.client), true));
                assertNull(f.upstream.pipeline().get(HttpClientCodec.class));
                assertNull(f.upstream.pipeline().get(HttpObjectAggregator.class));
                assertNotNull(f.client.pipeline().get(RelayHandler.class));
                assertTrue(f.client.config().isAutoRead());
                f.client.writeInbound(Unpooled.wrappedBuffer(new byte[]{1, 2, 3}));
                assertArrayEquals(new byte[]{1, 2, 3}, drain(f.upstream));
                f.upstream.writeInbound(Unpooled.wrappedBuffer(new byte[]{4, 5, 6}));
                assertArrayEquals(new byte[]{4, 5, 6}, drain(f.client));
            }
        }
    }

    @ParameterizedTest @EnumSource(Protocol.class)
    void coalescedLargeGreetingRespectsExistingBackpressure(Protocol protocol) {
        try (Fixture f = new Fixture(protocol, false)) {
            byte[] body = new byte[256 * 1024];
            for (int i = 0; i < body.length; i++) body[i] = (byte) i;
            f.client.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
            ByteBuf packet = Unpooled.buffer().writeBytes(HEADER).writeBytes(body);
            f.upstream.writeInbound(packet);
            f.upstream.runPendingTasks();
            assertFalse(f.upstream.config().isAutoRead());
            assertArrayEquals(body, f.stripResponse(drain(f.client), true));
            assertEquals(0, packet.refCnt());
            f.client.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
            f.client.runPendingTasks();
            assertTrue(f.upstream.config().isAutoRead());
        }
    }

    @ParameterizedTest @EnumSource(Protocol.class)
    void upstreamRejectionNeverInstallsRelayOrForwardsErrorBody(Protocol protocol) {
        try (Fixture f = new Fixture(protocol, false)) {
            f.upstream.writeInbound(Unpooled.copiedBuffer(
                    "HTTP/1.1 407 Proxy Authentication Required\r\nContent-Length: 6\r\n\r\nsecret",
                    StandardCharsets.US_ASCII));
            assertEquals(0, f.stripResponse(drain(f.client), false).length);
            assertNull(f.client.pipeline().get(RelayHandler.class));
            assertFalse(f.client.isActive());
            assertFalse(f.upstream.isActive());
        }
    }

    @ParameterizedTest @EnumSource(Protocol.class)
    void failedSuccessWriteClosesBothSidesWithoutRelaying(Protocol protocol) {
        try (Fixture f = new Fixture(protocol, true)) {
            ByteBuf packet = Unpooled.buffer().writeBytes(HEADER).writeBytes(GREETING);
            f.upstream.writeInbound(packet);
            f.upstream.runPendingTasks();
            assertFalse(f.client.isActive());
            assertFalse(f.upstream.isActive());
            assertEquals(0, drain(f.client).length);
            assertEquals(0, packet.refCnt());
        }
    }

    @ParameterizedTest @EnumSource(Protocol.class)
    void upstreamEofDuringHandshakeReturnsFailure(Protocol protocol) {
        try (Fixture f = new Fixture(protocol, false)) {
            f.upstream.close();
            assertEquals(0, f.stripResponse(drain(f.client), false).length);
            assertFalse(f.client.isActive());
        }
    }

    @ParameterizedTest @EnumSource(Protocol.class)
    void logsOnceAndCountsCoalescedGreetingAndBothDirections(Protocol protocol, @TempDir Path directory) throws Exception {
        Path file = directory.resolve("access.log");
        try (AccessLog access = new AccessLog(file.toString(), false)) {
            try (Fixture f = new Fixture(protocol, false, access)) {
                f.upstream.writeInbound(Unpooled.buffer().writeBytes(HEADER).writeBytes(GREETING));
                f.upstream.runPendingTasks();
                assertArrayEquals(GREETING, f.stripResponse(drain(f.client), true));
                f.client.writeInbound(Unpooled.wrappedBuffer(new byte[]{1, 2, 3}));
                assertArrayEquals(new byte[]{1, 2, 3}, drain(f.upstream));
            }
        }
        var lines = Files.readAllLines(file);
        assertEquals(1, lines.size());
        assertEquals(Integer.toString(GREETING.length + 3), lines.get(0).split(" ")[5]);
    }

    private static byte[] drain(EmbeddedChannel channel) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Object message;
        while ((message = channel.readOutbound()) != null) {
            try {
                assertInstanceOf(ByteBuf.class, message);
                ByteBuf buffer = (ByteBuf) message;
                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.readBytes(bytes);
                out.writeBytes(bytes);
            } finally { ReferenceCountUtil.release(message); }
        }
        return out.toByteArray();
    }

    private static final class Fixture implements AutoCloseable {
        final Protocol protocol;
        final EmbeddedChannel client = new EmbeddedChannel();
        final EmbeddedChannel upstream;

        Fixture(Protocol protocol, boolean failWrites) {
            this(protocol, failWrites, null);
        }

        Fixture(Protocol protocol, boolean failWrites, AccessLog access) {
            this.protocol = protocol;
            client.pipeline().addLast(switch (protocol) {
                case HTTP -> new HttpResponseEncoder();
                case SOCKS4 -> Socks4ServerEncoder.INSTANCE;
                case SOCKS5 -> Socks5ServerEncoder.DEFAULT;
            });
            if (failWrites) client.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
                @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                    ReferenceCountUtil.release(msg);
                    promise.setFailure(new IOException("simulated success response write failure"));
                }
            });
            client.pipeline().addLast("client", new ChannelInboundHandlerAdapter());
            ChannelHandlerContext context = client.pipeline().context("client");
            Config config = new Config("127.0.0.1", 0, 0, false, null, "proxy.test", 443,
                    false, "Basic dGVzdDp0ZXN0", 1000, 1048576, false, "/proxy.pac", "127.0.0.1",
                    null, "test", null, null, false, false, new NoProxyMatcher(""));
            ChannelHandler handler = switch (protocol) {
                case HTTP -> new HttpConnectHandler(context, "example.test", 443, config, access, 0, "test");
                case SOCKS4 -> new Socks4ConnectHandler(context, "example.test", 443, config, access, 0, "test");
                case SOCKS5 -> new Socks5ConnectHandler(context, "example.test", 443, config, access, 0, "test");
            };
            upstream = new EmbeddedChannel(new HttpClientCodec(), new HttpObjectAggregator(65536), handler) {
                @Override protected SocketAddress localAddress0() { return new InetSocketAddress("127.0.0.1", 1234); }
            };
            String request = new String(drain(upstream), StandardCharsets.US_ASCII);
            assertTrue(request.startsWith("CONNECT example.test:443 HTTP/1.1"));
            assertTrue(request.contains("proxy-authorization: Basic dGVzdDp0ZXN0"));
        }

        byte[] stripResponse(byte[] bytes, boolean success) {
            if (protocol == Protocol.HTTP) {
                String text = new String(bytes, StandardCharsets.ISO_8859_1);
                assertTrue(text.startsWith(success ? "HTTP/1.1 200" : "HTTP/1.1 407")
                        || (!success && text.startsWith("HTTP/1.1 502")));
                int end = text.indexOf("\r\n\r\n");
                assertTrue(end >= 0);
                return Arrays.copyOfRange(bytes, end + 4, bytes.length);
            }
            int length = protocol == Protocol.SOCKS4 ? 8 : 10;
            assertTrue(bytes.length >= length);
            assertEquals(protocol == Protocol.SOCKS4 ? (success ? 0x5a : 0x5b) : (success ? 0 : 1), bytes[1]);
            return Arrays.copyOfRange(bytes, length, bytes.length);
        }

        @Override public void close() { client.finishAndReleaseAll(); upstream.finishAndReleaseAll(); }
    }
}
