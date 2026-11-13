package xzy.fz.handler;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import xzy.fz.config.Config;
import xzy.fz.util.NoProxyMatcher;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RelayBackpressureIntegrationTest {
    record Route(String protocol, boolean direct, boolean tls) {}

    static Stream<Route> routes() {
        return Stream.of("CONNECT", "SOCKS4", "SOCKS5", "WebSocket")
                .flatMap(protocol -> Stream.of(new Route(protocol, true, false),
                        new Route(protocol, false, false)));
    }

    @ParameterizedTest @MethodSource("routes")
    void slowOriginPausesClientThenResumesWithoutDataLoss(Route route, @TempDir Path directory) throws Exception {
        exercise(route, directory);
    }

    static Stream<Route> tlsRoutes() {
        return Stream.of(new Route("CONNECT", false, true));
    }

    @ParameterizedTest @MethodSource("tlsRoutes")
    void slowTlsUpstreamPausesClientThenResumesWithoutDataLoss(Route route, @TempDir Path directory) throws Exception {
        exercise(route, directory);
    }

    private static void exercise(Route route, Path directory) throws Exception {
        byte[] payload = new byte[4 * 1024 * 1024 + 37];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        CountDownLatch consume = new CountDownLatch(1);
        EventLoopGroup group = new NioEventLoopGroup(1);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Channel proxy = null;
        ServerSocket backend = null;
        Socket client = null;
        try {
            SslContext upstreamSsl = null;
            if (route.tls()) {
                // Use the required JDK's keytool rather than optional certificate providers.
                Path store = directory.resolve("server.p12");
                Path keytoolLog = directory.resolve("keytool.log");
                String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
                Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                        "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                        "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "1",
                        "-storetype", "PKCS12", "-keystore", store.toString(),
                        "-storepass", "test-only", "-keypass", "test-only", "-noprompt")
                        .redirectErrorStream(true).redirectOutput(keytoolLog.toFile()).start();
                if (!keytool.waitFor(10, TimeUnit.SECONDS)) {
                    keytool.destroyForcibly();
                    fail("keytool timed out");
                }
                assertEquals(0, keytool.exitValue(), () -> keytoolLog.toString());
                KeyStore keys = KeyStore.getInstance("PKCS12");
                char[] password = "test-only".toCharArray();
                try (InputStream input = Files.newInputStream(store)) { keys.load(input, password); }
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(keys, password);
                SSLContext ssl = SSLContext.getInstance("TLS");
                ssl.init(kmf.getKeyManagers(), null, null);
                backend = ssl.getServerSocketFactory().createServerSocket();
                upstreamSsl = SslContextBuilder.forClient().trustManager(
                        (java.security.cert.X509Certificate) keys.getCertificate("server")).build();
            } else {
                backend = new ServerSocket();
            }
            // Small socket buffers make congestion reproducible without timing a huge transfer.
            backend.setReceiveBufferSize(16 * 1024);
            backend.bind(new InetSocketAddress("127.0.0.1", 0));
            backend.setSoTimeout(10000);
            int port = backend.getLocalPort();
            Config config = new Config("127.0.0.1", 0, 0, false, null,
                    route.tls() ? "localhost" : "127.0.0.1", port, route.tls(), null,
                    3000, 1048576, false, "/proxy.pac", "127.0.0.1", null,
                    "test", null, null, false, false,
                    new NoProxyMatcher(route.direct() ? "127.0.0.1" : ""));
            AtomicReference<Channel> frontend = new AtomicReference<>();
            AtomicLong bytesRead = new AtomicLong();
            SslContext ssl = upstreamSsl;
            proxy = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel ch) {
                            frontend.set(ch);
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                    if (msg instanceof io.netty.buffer.ByteBuf buffer)
                                        bytesRead.addAndGet(buffer.readableBytes());
                                    ctx.fireChannelRead(msg);
                                }
                            });
                            if (route.protocol().startsWith("SOCKS")) {
                                ch.pipeline().addLast("socks-unification", new SocksPortUnificationServerHandler());
                                ch.pipeline().addLast("socks5-handler", new Socks5Handler(config, ssl, null));
                            } else {
                                ch.pipeline().addLast(new HttpRequestDecoder());
                                ch.pipeline().addLast(new HttpResponseEncoder());
                                ch.pipeline().addLast("http-proxy-handler", new HttpProxyHandler(config, ssl, null));
                            }
                        }
                    }).bind("127.0.0.1", 0).sync().channel();
            ServerSocket origin = backend;
            Future<?> server = executor.submit(() -> {
                try (Socket socket = origin.accept()) {
                    socket.setSoTimeout(15000);
                    if (!route.direct() || route.protocol().equals("WebSocket")) {
                        readHeaders(socket.getInputStream());
                        String response = route.protocol().equals("WebSocket")
                                ? "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n"
                                    + "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"
                                : "HTTP/1.1 200 Connection Established\r\n\r\n";
                        socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    }
                    assertTrue(consume.await(10, TimeUnit.SECONDS));
                    byte[] received = socket.getInputStream().readNBytes(payload.length);
                    assertArrayEquals(payload, received);
                    socket.getOutputStream().write(received);
                    socket.getOutputStream().flush();
                    // Let the client close after it has received the echo, avoiding teardown races.
                    assertEquals(-1, socket.getInputStream().read());
                }
                return null;
            });
            client = new Socket("127.0.0.1", ((InetSocketAddress) proxy.localAddress()).getPort());
            client.setSoTimeout(15000);
            handshake(client, route.protocol(), port);
            Channel front = frontend.get();
            // Serialize after the deferred WebSocket codec removal and relay activation.
            front.eventLoop().submit(() -> {
                assertNotNull(front.pipeline().get(RelayHandler.class));
                assertEquals(32 * 1024, front.config().getWriteBufferWaterMark().low());
                assertEquals(64 * 1024, front.config().getWriteBufferWaterMark().high());
            }).get(5, TimeUnit.SECONDS);
            Socket sender = client;
            Future<?> upload = executor.submit(() -> {
                sender.getOutputStream().write(payload);
                return null;
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean paused = false;
            while (System.nanoTime() < deadline) {
                paused = front.eventLoop().submit(() -> !front.config().isAutoRead()).get(1, TimeUnit.SECONDS);
                if (paused) break;
                Thread.sleep(10);
            }
            assertTrue(paused, "slow origin must pause client reads: " + route);
            long before = front.eventLoop().submit(bytesRead::get).get(1, TimeUnit.SECONDS);
            Thread.sleep(100);
            assertEquals(before, front.eventLoop().submit(bytesRead::get).get(1, TimeUnit.SECONDS),
                    "paused reads must stop accepting additional payload");
            consume.countDown();
            upload.get(15, TimeUnit.SECONDS);
            assertArrayEquals(payload, client.getInputStream().readNBytes(payload.length));
            assertTrue(front.eventLoop().submit(() -> front.config().isAutoRead()).get(1, TimeUnit.SECONDS));
            client.close();
            server.get(10, TimeUnit.SECONDS);
        } finally {
            consume.countDown();
            if (client != null) client.close();
            if (backend != null) backend.close();
            if (proxy != null) proxy.close().sync();
            executor.shutdownNow();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    private static void handshake(Socket client, String protocol, int port) throws IOException {
        OutputStream out = client.getOutputStream();
        InputStream in = client.getInputStream();
        if (protocol.equals("SOCKS4")) {
            out.write(new byte[]{4, 1, (byte) (port >> 8), (byte) port, 127, 0, 0, 1, 0});
            byte[] response = in.readNBytes(8);
            assertEquals(8, response.length);
            assertEquals(0x5a, response[1]);
        } else if (protocol.equals("SOCKS5")) {
            out.write(new byte[]{5, 1, 0});
            assertArrayEquals(new byte[]{5, 0}, in.readNBytes(2));
            out.write(new byte[]{5, 1, 0, 1, 127, 0, 0, 1, (byte) (port >> 8), (byte) port});
            byte[] response = in.readNBytes(10);
            assertEquals(10, response.length);
            assertEquals(0, response[1]);
        } else {
            String authority = "127.0.0.1:" + port;
            String request = protocol.equals("CONNECT")
                    ? "CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority + "\r\n\r\n"
                    : "GET http://" + authority + "/socket HTTP/1.1\r\nHost: " + authority
                        + "\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\n"
                        + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            assertTrue(readHeaders(in).startsWith(protocol.equals("CONNECT") ? "HTTP/1.1 200" : "HTTP/1.1 101"));
        }
    }

    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int state = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        while (state < end.length) {
            int next = in.read();
            if (next < 0) throw new EOFException();
            result.write(next);
            state = next == end[state] ? state + 1 : (next == '\r' ? 1 : 0);
        }
        return result.toString(StandardCharsets.US_ASCII);
    }
}
