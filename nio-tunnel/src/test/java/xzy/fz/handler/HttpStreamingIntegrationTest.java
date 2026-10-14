package xzy.fz.handler;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import org.junit.jupiter.api.Test;
import xzy.fz.config.Config;
import xzy.fz.util.NoProxyMatcher;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real sockets exercise decoding, async connect, framing, and both routing paths. */
class HttpStreamingIntegrationTest {
    @Test
    void sendsUploadChunkBeforeRequestCompletes() throws Exception {
        for (boolean direct : new boolean[]{false, true}) {
            CountDownLatch received = new CountDownLatch(1);
            try (Fixture f = new Fixture(direct); Socket client = f.client()) {
                Future<?> server = f.serve(socket -> {
                    headers(socket.getInputStream());
                    assertEquals("first", ascii(socket.getInputStream().readNBytes(5)));
                    received.countDown();
                    assertEquals("second", ascii(socket.getInputStream().readNBytes(6)));
                    send(socket, "HTTP/1.1 204 No Content\r\n\r\n");
                });
                send(client, f.request("POST", "/stream-upload", "Content-Length: 11\r\n") + "first");
                assertTrue(received.await(5, TimeUnit.SECONDS), "upload was buffered");
                send(client, "second");
                assertTrue(headers(client.getInputStream()).startsWith("HTTP/1.1 204"));
                server.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void preservesWebSocketUpgradeAndCoalescedFrameOnRealSockets() throws Exception {
        byte[] frame = {(byte) 0x81, 2, 'h', 'i'};
        byte[] ping = {(byte) 0x89, (byte) 0x80, 1, 2, 3, 4};
        for (boolean direct : new boolean[]{false, true}) {
            try (Fixture f = new Fixture(direct); Socket client = f.client()) {
                Future<?> server = f.serve(socket -> {
                    assertTrue(headers(socket.getInputStream()).contains("Upgrade: websocket"));
                    byte[] response = ("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\n"
                            + "Upgrade: websocket\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII);
                    ByteArrayOutputStream packet = new ByteArrayOutputStream();
                    packet.write(response);
                    packet.write(frame);
                    socket.getOutputStream().write(packet.toByteArray());
                    assertArrayEquals(ping, socket.getInputStream().readNBytes(ping.length));
                });
                send(client, f.request("GET", "/socket", "Connection: Upgrade\r\nUpgrade: websocket\r\n"
                        + "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"));
                assertTrue(headers(client.getInputStream()).startsWith("HTTP/1.1 101"));
                assertArrayEquals(frame, client.getInputStream().readNBytes(frame.length));
                client.getOutputStream().write(ping);
                server.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void closesAfterEarlyFinalResponseWithoutWaitingForUpload() throws Exception {
        for (boolean direct : new boolean[]{false, true}) {
            try (Fixture f = new Fixture(direct); Socket client = f.client()) {
                Future<?> server = f.serve(socket -> {
                    headers(socket.getInputStream());
                    send(socket, "HTTP/1.1 413 Content Too Large\r\nContent-Length: 0\r\n\r\n");
                });
                send(client, f.request("POST", "/reject", "Content-Length: 2000000\r\nExpect: 100-continue\r\n"));
                assertTrue(headers(client.getInputStream()).startsWith("HTTP/1.1 413"));
                assertEquals(-1, client.getInputStream().read());
                server.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void headResponseDoesNotWaitForAdvertisedBody() throws Exception {
        for (boolean direct : new boolean[]{false, true}) {
            try (Fixture f = new Fixture(direct); Socket client = f.client()) {
                Future<?> server = f.serve(socket -> {
                    headers(socket.getInputStream());
                    send(socket, "HTTP/1.1 200 OK\r\nContent-Length: 3000000\r\n\r\n");
                    assertEquals(-1, socket.getInputStream().read());
                });
                send(client, f.request("HEAD", "/head", ""));
                assertTrue(headers(client.getInputStream()).contains("3000000"));
                server.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void streamsLargePostAndPutBodies() throws Exception {
        byte[] body = new byte[2 * 1024 * 1024 + 37];
        for (int i = 0; i < body.length; i++) body[i] = (byte) i;
        for (boolean direct : new boolean[]{false, true}) {
            for (String method : new String[]{"POST", "PUT"}) {
                try (Fixture f = new Fixture(direct); Socket client = f.client()) {
                    Future<?> server = f.serve(socket -> {
                        String headers = headers(socket.getInputStream());
                        assertTrue(headers.startsWith(method + (direct ? " /upload " : " http://")));
                        assertArrayEquals(body, socket.getInputStream().readNBytes(body.length));
                        send(socket, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK");
                    });
                    send(client, f.request(method, "/upload", "Content-Length: " + body.length + "\r\n"));
                    client.getOutputStream().write(body);
                    assertTrue(headers(client.getInputStream()).startsWith("HTTP/1.1 200"));
                    assertEquals("OK", ascii(client.getInputStream().readNBytes(2)));
                    server.get(10, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void forwardsChunkedUploadAndTrailers() throws Exception {
        for (boolean direct : new boolean[]{false, true}) {
            try (Fixture f = new Fixture(direct); Socket client = f.client()) {
                Future<?> server = f.serve(socket -> {
                    assertTrue(headers(socket.getInputStream()).toLowerCase(Locale.ROOT)
                            .contains("transfer-encoding: chunked"));
                    assertEquals("firstsecond", chunkedBody(socket.getInputStream(), "X-Checksum: done"));
                    send(socket, "HTTP/1.1 204 No Content\r\n\r\n");
                });
                send(client, f.request("PUT", "/upload", "Transfer-Encoding: chunked\r\nTrailer: X-Checksum\r\n")
                        + "5\r\nfirst\r\n6\r\nsecond\r\n0\r\nX-Checksum: done\r\n\r\n");
                assertTrue(headers(client.getInputStream()).startsWith("HTTP/1.1 204"));
                server.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void relaysContinueBeforeClientSendsBody() throws Exception {
        for (boolean direct : new boolean[]{false, true}) {
            try (Fixture f = new Fixture(direct); Socket client = f.client()) {
                Future<?> server = f.serve(socket -> {
                    assertTrue(headers(socket.getInputStream()).contains("100-continue"));
                    send(socket, "HTTP/1.1 100 Continue\r\n\r\n");
                    assertEquals("hello", ascii(socket.getInputStream().readNBytes(5)));
                    send(socket, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK");
                });
                send(client, f.request("POST", "/upload", "Content-Length: 5\r\nExpect: 100-continue\r\n"));
                assertTrue(headers(client.getInputStream()).startsWith("HTTP/1.1 100"));
                send(client, "hello");
                assertTrue(headers(client.getInputStream()).startsWith("HTTP/1.1 200"));
                assertEquals("OK", ascii(client.getInputStream().readNBytes(2)));
                server.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void streamsResponsesLargerThanOneMiB() throws Exception {
        byte[] body = new byte[2 * 1024 * 1024 + 19];
        Arrays.fill(body, (byte) 'x');
        for (boolean direct : new boolean[]{false, true}) {
            try (Fixture f = new Fixture(direct); Socket client = f.client()) {
                Future<?> server = f.serve(socket -> {
                    headers(socket.getInputStream());
                    send(socket, "HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\n\r\n");
                    socket.getOutputStream().write(body);
                });
                send(client, f.request("GET", "/large", ""));
                assertTrue(headers(client.getInputStream()).startsWith("HTTP/1.1 200"));
                assertArrayEquals(body, client.getInputStream().readNBytes(body.length));
                server.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void deliversSseChunkBeforeResponseCompletesAndPreservesTrailers() throws Exception {
        for (boolean direct : new boolean[]{false, true}) {
            CountDownLatch received = new CountDownLatch(1);
            try (Fixture f = new Fixture(direct); Socket client = f.client()) {
                Future<?> server = f.serve(socket -> {
                    headers(socket.getInputStream());
                    send(socket, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                            + "Transfer-Encoding: chunked\r\nTrailer: X-End\r\n\r\n9\r\ndata: 1\n\n\r\n");
                    assertTrue(received.await(5, TimeUnit.SECONDS), "first event was buffered");
                    send(socket, "9\r\ndata: 2\n\n\r\n0\r\nX-End: yes\r\n\r\n");
                });
                send(client, f.request("GET", "/events", ""));
                assertTrue(headers(client.getInputStream()).contains("text/event-stream"));
                assertEquals("data: 1\n\n", chunk(client.getInputStream()));
                received.countDown();
                assertEquals("data: 2\n\n", chunkedBody(client.getInputStream(), "X-End: yes"));
                server.get(10, TimeUnit.SECONDS);
            } finally {
                received.countDown();
            }
        }
    }

    @Test
    void serializesPipelinedRequestsOnKeepAliveConnection() throws Exception {
        for (boolean direct : new boolean[]{false, true}) {
            try (Fixture f = new Fixture(direct); Socket client = f.client()) {
                Future<?> first = f.serve(socket -> {
                    assertTrue(headers(socket.getInputStream()).contains("/first"));
                    assertEquals("one", ascii(socket.getInputStream().readNBytes(3)));
                    send(socket, "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\none");
                });
                send(client, f.request("POST", "/first", "Content-Length: 3\r\n") + "one"
                        + f.request("GET", "/second", ""));
                assertTrue(headers(client.getInputStream()).startsWith("HTTP/1.1 200"));
                assertEquals("one", ascii(client.getInputStream().readNBytes(3)));
                first.get(10, TimeUnit.SECONDS);
                Future<?> second = f.serve(socket -> {
                    assertTrue(headers(socket.getInputStream()).contains("/second"));
                    send(socket, "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\ntwo");
                });
                assertTrue(headers(client.getInputStream()).startsWith("HTTP/1.1 200"));
                assertEquals("two", ascii(client.getInputStream().readNBytes(3)));
                second.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void forwardsEofDelimitedResponse() throws Exception {
        for (boolean direct : new boolean[]{false, true}) {
            try (Fixture f = new Fixture(direct); Socket client = f.client()) {
                Future<?> server = f.serve(socket -> {
                    headers(socket.getInputStream());
                    send(socket, "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nhello");
                });
                send(client, f.request("GET", "/eof", ""));
                assertTrue(headers(client.getInputStream()).toLowerCase(Locale.ROOT).contains("chunked"));
                assertEquals("hello", chunkedBody(client.getInputStream(), null));
                assertEquals(-1, client.getInputStream().read());
                server.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void closesTruncatedResponseWithoutAppendingBadGateway() throws Exception {
        for (boolean direct : new boolean[]{false, true}) {
            try (Fixture f = new Fixture(direct); Socket client = f.client()) {
                Future<?> server = f.serve(socket -> {
                    headers(socket.getInputStream());
                    send(socket, "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nshort");
                });
                send(client, f.request("GET", "/broken", ""));
                assertTrue(headers(client.getInputStream()).startsWith("HTTP/1.1 200"));
                assertEquals("short", ascii(client.getInputStream().readAllBytes()));
                server.get(10, TimeUnit.SECONDS);
            }
        }
    }

    private static String headers(InputStream in) throws IOException {
        StringBuilder result = new StringBuilder();
        String line;
        while (!(line = line(in)).isEmpty()) result.append(line).append("\r\n");
        return result.toString();
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') return out.toString(StandardCharsets.US_ASCII).replaceFirst("\r$", "");
            out.write(b);
        }
        throw new EOFException("Expected HTTP line: " + out);
    }

    private static String chunk(InputStream in) throws IOException {
        int size = Integer.parseInt(line(in).split(";", 2)[0], 16);
        assertTrue(size > 0);
        String body = ascii(in.readNBytes(size));
        assertEquals("", line(in));
        return body;
    }

    private static String chunkedBody(InputStream in, String trailer) throws IOException {
        StringBuilder body = new StringBuilder();
        while (true) {
            int size = Integer.parseInt(line(in).split(";", 2)[0], 16);
            if (size == 0) {
                String trailers = headers(in);
                if (trailer != null) assertTrue(trailers.toLowerCase(Locale.ROOT)
                        .contains(trailer.toLowerCase(Locale.ROOT)), trailers);
                return body.toString();
            }
            body.append(ascii(in.readNBytes(size)));
            assertEquals("", line(in));
        }
    }

    private static String ascii(byte[] bytes) {
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static void send(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    @FunctionalInterface
    private interface ServerAction { void run(Socket socket) throws Exception; }

    private static final class Fixture implements AutoCloseable {
        final ServerSocket backend = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        final EventLoopGroup group = new NioEventLoopGroup(1);
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final Channel proxy;

        Fixture(boolean direct) throws Exception {
            backend.setSoTimeout(5000);
            Config config = new Config("127.0.0.1", 0, 0, false, null,
                    "127.0.0.1", backend.getLocalPort(), false, null, 3000, 1048576,
                    false, "/proxy.pac", "127.0.0.1", null, "test", null, null, false, false,
                    new NoProxyMatcher(direct ? "127.0.0.1" : ""));
            proxy = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new HttpRequestDecoder(1048576, 1048576, 1048576));
                            ch.pipeline().addLast(new HttpResponseEncoder());
                            ch.pipeline().addLast("http-proxy-handler", new HttpProxyHandler(config, null, null));
                        }
                    }).bind("127.0.0.1", 0).sync().channel();
        }

        Socket client() throws IOException {
            Socket socket = new Socket("127.0.0.1", ((InetSocketAddress) proxy.localAddress()).getPort());
            socket.setSoTimeout(5000);
            return socket;
        }

        String request(String method, String path, String extra) {
            String authority = "127.0.0.1:" + backend.getLocalPort();
            return method + " http://" + authority + path + " HTTP/1.1\r\nHost: " + authority + "\r\n" + extra + "\r\n";
        }

        Future<?> serve(ServerAction action) {
            return executor.submit(() -> {
                try (Socket socket = backend.accept()) {
                    socket.setSoTimeout(5000);
                    action.run(socket);
                }
                return null;
            });
        }

        @Override public void close() throws Exception {
            backend.close();
            executor.shutdownNow();
            proxy.close().sync();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }
}
