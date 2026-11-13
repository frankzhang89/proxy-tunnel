package xzy.fz.handler;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import xzy.fz.config.Config;
import xzy.fz.config.ConfigLoader;
import xzy.fz.util.NoProxyMatcher;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class Socks4AuthenticationIntegrationTest {
    private static final String USER = "test-user";
    private static final String PASSWORD = "test-password";
    record Request(boolean socks4a, boolean direct, String userId) {}

    static Stream<Request> rejectedRequests() {
        return Stream.of(false, true).flatMap(socks4a -> Stream.of(false, true)
                .flatMap(direct -> Stream.of("", "arbitrary", USER)
                        .map(userId -> new Request(socks4a, direct, userId))));
    }

    static Stream<Request> allowedRequests() {
        return Stream.of(false, true).flatMap(socks4a -> Stream.of(false, true)
                .map(direct -> new Request(socks4a, direct, "arbitrary")));
    }

    @ParameterizedTest @MethodSource("rejectedRequests")
    void requiredAuthenticationRejectsSocks4And4aWithoutOpeningAnyConnection(Request request) throws Exception {
        try (Fixture fixture = new Fixture(true, request.direct(), false); Socket client = fixture.client()) {
            sendSocks4(client, fixture.target.getLocalPort(), request);
            byte[] response = client.getInputStream().readNBytes(8);
            assertArrayEquals(new byte[]{0, 0x5b, 0, 0, 0, 0, 0, 0}, response);
            assertEquals(-1, client.getInputStream().read(), "rejection must close the client");
            fixture.assertNoConnections();
        }
    }

    @ParameterizedTest @MethodSource("allowedRequests")
    void authenticationDisabledPreservesSocks4And4aRoutingAndBothDirections(Request request) throws Exception {
        try (Fixture fixture = new Fixture(false, request.direct(), false); Socket client = fixture.client()) {
            sendSocks4(client, fixture.target.getLocalPort(), request);
            try (Socket peer = fixture.accept(request.direct())) {
                if (!request.direct()) {
                    String connect = readHeaders(peer.getInputStream());
                    assertTrue(connect.startsWith("CONNECT " + (request.socks4a() ? "localhost" : "127.0.0.1")
                            + ":" + fixture.target.getLocalPort() + " HTTP/1.1"));
                    send(peer, "HTTP/1.1 200 Connection Established\r\n\r\n");
                }
                byte[] response = client.getInputStream().readNBytes(8);
                assertEquals(8, response.length);
                assertEquals(0x5a, response[1]);
                assertBidirectionalData(client, peer);
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void socks5PasswordAuthenticationStillRejectsWrongPasswordAndAcceptsCorrectPassword(boolean correct) throws Exception {
        try (Fixture fixture = new Fixture(true, true, false); Socket client = fixture.client()) {
            client.getOutputStream().write(new byte[]{5, 1, 2});
            assertArrayEquals(new byte[]{5, 2}, client.getInputStream().readNBytes(2));
            byte[] user = USER.getBytes(StandardCharsets.US_ASCII);
            byte[] password = (correct ? PASSWORD : "wrong").getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream auth = new ByteArrayOutputStream();
            auth.write(1);
            auth.write(user.length);
            auth.write(user);
            auth.write(password.length);
            auth.write(password);
            client.getOutputStream().write(auth.toByteArray());
            byte[] authResponse = client.getInputStream().readNBytes(2);
            assertEquals(2, authResponse.length);
            assertEquals(1, authResponse[0]);
            if (correct) assertEquals(0, authResponse[1]);
            else assertNotEquals(0, authResponse[1]);
            if (!correct) {
                assertEquals(-1, client.getInputStream().read());
                fixture.assertNoConnections();
            } else {
                int port = fixture.target.getLocalPort();
                client.getOutputStream().write(new byte[]{5, 1, 0, 1, 127, 0, 0, 1, (byte) (port >> 8), (byte) port});
                try (Socket peer = fixture.accept(true)) {
                    byte[] response = client.getInputStream().readNBytes(10);
                    assertEquals(10, response.length);
                    assertEquals(0, response[1]);
                    assertBidirectionalData(client, peer);
                }
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "wrong", "correct"})
    void httpProxyAuthenticationStillRequiresCorrectCredentials(String auth) throws Exception {
        try (Fixture fixture = new Fixture(true, false, true); Socket client = fixture.client()) {
            String header = switch (auth) {
                case "correct" -> ConfigLoader.basicHeader(USER, PASSWORD);
                case "wrong" -> ConfigLoader.basicHeader(USER, "wrong");
                default -> null;
            };
            send(client, "GET http://127.0.0.1:" + fixture.target.getLocalPort() + "/ HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + (header == null ? "" : "Proxy-Authorization: " + header + "\r\n") + "\r\n");
            if (auth.equals("correct")) {
                try (Socket peer = fixture.accept(false)) {
                    String request = readHeaders(peer.getInputStream());
                    assertTrue(request.startsWith("GET http://"));
                    assertFalse(request.toLowerCase(java.util.Locale.ROOT).contains("proxy-authorization:"));
                    send(peer, "HTTP/1.1 204 No Content\r\n\r\n");
                    assertTrue(readHeaders(client.getInputStream()).startsWith("HTTP/1.1 204"));
                }
            } else {
                assertTrue(readHeaders(client.getInputStream()).startsWith("HTTP/1.1 407"));
                assertEquals(-1, client.getInputStream().read());
                fixture.assertNoConnections();
            }
        }
    }

    private static void sendSocks4(Socket client, int port, Request request) throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        packet.write(new byte[]{4, 1, (byte) (port >> 8), (byte) port});
        packet.write(request.socks4a() ? new byte[]{0, 0, 0, 1} : new byte[]{127, 0, 0, 1});
        packet.write(request.userId().getBytes(StandardCharsets.US_ASCII));
        packet.write(0);
        if (request.socks4a()) {
            packet.write("localhost".getBytes(StandardCharsets.US_ASCII));
            packet.write(0);
        }
        client.getOutputStream().write(packet.toByteArray());
    }

    private static void assertBidirectionalData(Socket client, Socket peer) throws IOException {
        send(client, "client-data");
        assertArrayEquals("client-data".getBytes(StandardCharsets.US_ASCII), peer.getInputStream().readNBytes(11));
        send(peer, "server-data");
        assertArrayEquals("server-data".getBytes(StandardCharsets.US_ASCII), client.getInputStream().readNBytes(11));
    }

    private static void send(Socket socket, String message) throws IOException {
        socket.getOutputStream().write(message.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static String readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] end = {'\r', '\n', '\r', '\n'};
        int state = 0;
        while (state < end.length) {
            int next = input.read();
            if (next < 0) throw new EOFException();
            result.write(next);
            state = next == end[state] ? state + 1 : (next == '\r' ? 1 : 0);
        }
        return result.toString(StandardCharsets.US_ASCII);
    }

    private static final class Fixture implements AutoCloseable {
        final ServerSocket target = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        final ServerSocket upstream = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        final EventLoopGroup group = new NioEventLoopGroup(1);
        final Channel proxy;

        Fixture(boolean auth, boolean direct, boolean http) throws Exception {
            Config config = new Config("127.0.0.1", 0, 0, auth,
                    auth ? ConfigLoader.basicHeader(USER, PASSWORD) : null,
                    "127.0.0.1", upstream.getLocalPort(), false, null, 1000, 1048576,
                    false, "/proxy.pac", "127.0.0.1", null, "test", null, null, false, false,
                    new NoProxyMatcher(direct ? "127.0.0.1,localhost" : ""));
            proxy = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel ch) {
                            if (http) {
                                ch.pipeline().addLast(new HttpRequestDecoder());
                                ch.pipeline().addLast(new HttpResponseEncoder());
                                ch.pipeline().addLast("http-proxy-handler", new HttpProxyHandler(config, null, null));
                            } else {
                                ch.pipeline().addLast("socks-unification", new SocksPortUnificationServerHandler());
                                ch.pipeline().addLast("socks5-handler", new Socks5Handler(config, null, null));
                            }
                        }
                    }).bind("127.0.0.1", 0).sync().channel();
        }

        Socket client() throws IOException {
            Socket socket = new Socket("127.0.0.1", ((InetSocketAddress) proxy.localAddress()).getPort());
            socket.setSoTimeout(3000);
            return socket;
        }

        Socket accept(boolean direct) throws IOException {
            ServerSocket listener = direct ? target : upstream;
            listener.setSoTimeout(3000);
            Socket socket = listener.accept();
            socket.setSoTimeout(3000);
            return socket;
        }

        void assertNoConnections() throws IOException {
            for (ServerSocket listener : new ServerSocket[]{target, upstream}) {
                listener.setSoTimeout(100);
                assertThrows(SocketTimeoutException.class, () -> {
                    try (Socket unexpected = listener.accept()) {
                        fail("Authentication rejection opened an outbound connection to " + listener.getLocalPort());
                    }
                });
            }
        }

        @Override public void close() throws Exception {
            target.close();
            upstream.close();
            proxy.close().sync();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }
}
