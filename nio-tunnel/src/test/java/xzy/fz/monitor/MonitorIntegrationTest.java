package xzy.fz.monitor;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import xzy.fz.config.*;
import xzy.fz.handler.*;
import xzy.fz.util.NoProxyMatcher;

class MonitorIntegrationTest {
  static String headers(InputStream in) throws IOException {
    var b = new ByteArrayOutputStream();
    int c;
    while ((c = in.read()) != -1) {
      b.write(c);
      if (b.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) break;
    }
    return b.toString(StandardCharsets.US_ASCII);
  }

  static void send(OutputStream out, String s) throws IOException {
    out.write(s.getBytes(StandardCharsets.US_ASCII));
    out.flush();
  }

  @ParameterizedTest
  @CsvSource({
    "http_connect,false",
    "http_connect,true",
    "socks4,false",
    "socks4,true",
    "socks5,false",
    "socks5,true",
    "http,false",
    "http,true",
    "websocket,false",
    "websocket,true"
  })
  void realTransfersAndProcessLookup(String protocol, boolean direct) throws Exception {
    try (ServerSocket target = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
      int port = target.getLocalPort();
      var serving =
          CompletableFuture.runAsync(
              () -> {
                try (Socket s = target.accept()) {
                  s.setSoTimeout(4000);
                  var in = s.getInputStream();
                  var out = s.getOutputStream();
                  if (protocol.equals("http")) {
                    headers(in);
                    send(out, "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello");
                    return;
                  }
                  if (protocol.equals("websocket")) {
                    headers(in);
                    send(
                        out,
                        "HTTP/1.1 101 Switching Protocols\r\n"
                            + "Connection: Upgrade\r\n"
                            + "Upgrade: websocket\r\n"
                            + "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n");
                  } else if (!direct) {
                    assertTrue(headers(in).startsWith("CONNECT "));
                    send(out, "HTTP/1.1 200 Connection Established\r\n\r\n");
                  }
                  out.write(in.readNBytes(5));
                  out.flush();
                  Thread.sleep(2200);
                } catch (Exception e) {
                  throw new CompletionException(e);
                }
              });
      Config config =
          new Config(
              "127.0.0.1",
              0,
              0,
              false,
              null,
              "127.0.0.1",
              port,
              false,
              null,
              2000,
              4096,
              false,
              "/pac",
              "127.0.0.1",
              null,
              "test",
              null,
              null,
              false,
              false,
              new NoProxyMatcher(direct ? "127.0.0.1" : ""),
              new UiConfig(true, "127.0.0.1", 8390, 300, 1000, true));
      EventLoopGroup group = new NioEventLoopGroup(1);
      try (Monitor m = new Monitor(config)) {
        m.start();
        Channel listener =
            new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(
                    new ChannelInitializer<SocketChannel>() {
                      protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                            .addLast(
                                new ChannelInboundHandlerAdapter() {
                                  public void channelActive(ChannelHandlerContext ctx) {
                                    m.accept(ctx.channel());
                                    ctx.fireChannelActive();
                                  }
                                });
                        if (protocol.startsWith("socks"))
                          ch.pipeline()
                              .addLast(
                                  new SocksPortUnificationServerHandler(),
                                  new Socks5Handler(config, null, null));
                        else
                          ch.pipeline()
                              .addLast(
                                  new HttpRequestDecoder(),
                                  new HttpResponseEncoder(),
                                  new HttpProxyHandler(config, null, null));
                      }
                    })
                .bind("127.0.0.1", 0)
                .sync()
                .channel();
        try (Socket client =
            new Socket("127.0.0.1", ((InetSocketAddress) listener.localAddress()).getPort())) {
          client.setSoTimeout(4000);
          var in = client.getInputStream();
          var out = client.getOutputStream();
          if (protocol.equals("socks4")) {
            out.write(new byte[] {4, 1, (byte) (port >> 8), (byte) port, 127, 0, 0, 1, 0});
            out.flush();
            assertEquals(90, in.readNBytes(8)[1]);
          } else if (protocol.equals("socks5")) {
            out.write(new byte[] {5, 1, 0});
            out.flush();
            assertArrayEquals(new byte[] {5, 0}, in.readNBytes(2));
            out.write(new byte[] {5, 1, 0, 1, 127, 0, 0, 1, (byte) (port >> 8), (byte) port});
            out.flush();
            assertEquals(0, in.readNBytes(10)[1]);
          } else if (protocol.equals("http_connect")) {
            send(out, "CONNECT 127.0.0.1:" + port + " HTTP/1.1\r\nHost: x\r\n\r\n");
            assertTrue(headers(in).contains("200"));
          } else {
            send(
                out,
                "GET http://127.0.0.1:"
                    + port
                    + "/ HTTP/1.1\r\nHost: x\r\n"
                    + (protocol.equals("websocket")
                        ? "Connection: Upgrade\r\n"
                            + "Upgrade: websocket\r\n"
                            + "Sec-WebSocket-Version: 13\r\n"
                        : "")
                    + "\r\n");
            headers(in);
          }
          if (!protocol.equals("http")) send(out, "hello");
          assertEquals("hello", new String(in.readNBytes(5), StandardCharsets.US_ASCII));
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
          do {
            m.sample();
            if (m.snapshot().connections().getFirst().process().pid() != null) break;
            Thread.sleep(30);
          } while (System.nanoTime() < deadline && !protocol.equals("http"));
          m.sample();
          var row = m.snapshot().connections().getFirst();
          assertEquals(direct ? "direct" : "upstream", row.route());
          assertTrue(row.uploadBytes() > 0);
          assertTrue(row.downloadBytes() >= 5);
          assertEquals(client.getLocalPort(), row.source().port());
          if (!protocol.equals("http")) {
            assertEquals(
                ProcessHandle.current().pid(), row.process().pid(), row.process().reason());
            assertNotNull(row.process().name());
          }
        } finally {
          listener.close().sync();
        }
        serving.get(5, TimeUnit.SECONDS);
      } finally {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
      }
    }
  }

  @Test
  void servesAssetsAndSnapshot() throws Exception {
    int port;
    EventLoopGroup probeGroup = new NioEventLoopGroup(1);
    try {
      Channel probe =
          new ServerBootstrap()
              .group(probeGroup)
              .channel(NioServerSocketChannel.class)
              .childHandler(new ChannelInboundHandlerAdapter())
              .bind("127.0.0.1", 0)
              .sync()
              .channel();
      port = ((InetSocketAddress) probe.localAddress()).getPort();
      probe.close().sync();
    } finally {
      probeGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }
    try (MonitorServer server = new MonitorServer(MonitorTest.config(port, false))) {
      server.start();
      var client = java.net.http.HttpClient.newHttpClient();
      for (String path : new String[] {"/", "/app.css", "/app.js", "/api/snapshot"}) {
        var response =
            client.send(
                java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertFalse(response.body().isBlank());
        if (path.equals("/api/snapshot")) {
          assertEquals(
              ProcessHandle.current().pid(),
              com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject()
                  .get("serverPid").getAsLong());
        }
      }
    }
  }
}
