package xzy.fz.monitor;

import com.google.gson.Gson;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import xzy.fz.config.Config;

/** Loopback-only, read-only UI. Its event loops never run proxy traffic or OS lookups. */
public final class MonitorServer implements AutoCloseable {
  private final Config config;
  private final EventLoopGroup group = new NioEventLoopGroup(1);
  private final ExecutorService requests =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(128),
          r -> {
            Thread t = new Thread(r, "monitor-api");
            t.setDaemon(true);
            return t;
          });
  private final Gson gson = new Gson();
  private Channel server;
  private Monitor monitor;

  public MonitorServer(Config config) {
    this.config = config;
  }

  public Monitor start() throws Exception {
    InetAddress host = InetAddress.getByName(config.ui().host());
    if (!host.isLoopbackAddress())
      throw new IllegalArgumentException("ui.host must be a loopback address");
    if (config.ui().port() < 1
        || config.ui().port() > 65535
        || config.ui().historySeconds() < 0
        || config.ui().historyMaxEntries() < 0)
      throw new IllegalArgumentException("Invalid UI port or history limit");
    monitor = new Monitor(config);
    server =
        new ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel.class)
            .childHandler(
                new ChannelInitializer<SocketChannel>() {
                  protected void initChannel(SocketChannel ch) {
                    ch.pipeline()
                        .addLast(
                            new HttpServerCodec(), new HttpObjectAggregator(4096), new Handler());
                  }
                })
            .bind(host, config.ui().port())
            .sync()
            .channel();
    monitor.start();
    return monitor;
  }

  final class Handler extends SimpleChannelInboundHandler<FullHttpRequest> {
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
      // Reject rebinding/cross-origin reads; proxy authentication is deliberately unrelated.
      String expected = "[" + config.ui().host() + "]:" + config.ui().port();
      String host = request.headers().get(HttpHeaderNames.HOST, "");
      if (!host.equalsIgnoreCase(config.ui().host() + ":" + config.ui().port())
          && !host.equalsIgnoreCase(expected)
          && !host.equalsIgnoreCase("localhost:" + config.ui().port())) {
        respond(ctx, 403, "text/plain", "Forbidden".getBytes(StandardCharsets.UTF_8));
        return;
      }
      if (request.method() != HttpMethod.GET) {
        respond(ctx, 405, "text/plain", new byte[0]);
        return;
      }
      QueryStringDecoder query;
      try {
        query = new QueryStringDecoder(request.uri());
      } catch (IllegalArgumentException e) {
        respond(ctx, 400, "text/plain", new byte[0]);
        return;
      }
      if (query.path().equals("/api/snapshot")) {
        try {
          requests.execute(
              () -> {
                try {
                  respond(
                      ctx,
                      200,
                      "application/json",
                      gson.toJson(view(monitor.snapshot(), query.parameters()))
                          .getBytes(StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                  respond(ctx, 400, "text/plain", "Invalid query".getBytes(StandardCharsets.UTF_8));
                }
              });
        } catch (RejectedExecutionException e) {
          respond(ctx, 503, "text/plain", new byte[0]);
        }
      } else {
        String resource =
            switch (query.path()) {
              case "/" -> "index.html";
              case "/app.css" -> "app.css";
              case "/app.js" -> "app.js";
              case "/traffic-chart.js" -> "traffic-chart.js";
              case "/vendor/uplot-1.6.32/uPlot.iife.min.js" -> "vendor/uplot-1.6.32/uPlot.iife.min.js";
              case "/vendor/uplot-1.6.32/uPlot.min.css" -> "vendor/uplot-1.6.32/uPlot.min.css";
              default -> null;
            };
        if (resource == null) {
          respond(ctx, 404, "text/plain", new byte[0]);
          return;
        }
        try (var in = getClass().getResourceAsStream("/web/" + resource)) {
          if (in == null) {
            respond(ctx, 404, "text/plain", new byte[0]);
            return;
          }
          respond(
              ctx,
              200,
              resource.endsWith("css")
                  ? "text/css"
                  : resource.endsWith("js") ? "application/javascript" : "text/html",
              in.readAllBytes());
        } catch (Exception e) {
          respond(ctx, 500, "text/plain", new byte[0]);
        }
      }
    }

    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      ctx.close();
    }
  }

  static String value(Map<String, List<String>> q, String key, String fallback) {
    return q.getOrDefault(key, List.of(fallback)).getFirst();
  }

  static Map<String, Object> view(Monitor.Snapshot s, Map<String, List<String>> q) {
    String scope = value(q, "scope", "active"),
        route = value(q, "route", "all"),
        protocol = value(q, "protocol", "all"),
        search = value(q, "q", "").toLowerCase(Locale.ROOT);
    if (!Set.of("active", "recent", "all").contains(scope)
        || !Set.of("all", "upstream", "direct").contains(route)
        || !Set.of("all", "http", "http_connect", "socks4", "socks5", "websocket")
            .contains(protocol)
        || search.length() > 256) throw new IllegalArgumentException();
    int page = Math.max(1, Integer.parseInt(value(q, "page", "1"))),
        size = Math.max(1, Math.min(200, Integer.parseInt(value(q, "pageSize", "50"))));
    List<Monitor.Connection> rows =
        s.connections().stream()
            .filter(r -> scope.equals("all") || (scope.equals("active") == (r.endedAt() == null)))
            .filter(r -> route.equals("all") || route.equals(r.route()))
            .filter(r -> protocol.equals("all") || protocol.equals(r.protocol()))
            .filter(
                r ->
                    search.isEmpty()
                        || (r.source().host()
                                + " "
                                + r.source().port()
                                + " "
                                + r.targetHost()
                                + " "
                                + r.upstreamHost()
                                + " "
                                + r.process().pid()
                                + " "
                                + r.process().name())
                            .toLowerCase(Locale.ROOT)
                            .contains(search))
            .sorted(
                ConnectionOrder.comparator(value(q, "sort", ""), value(q, "order", "asc"), scope))
            .toList();
    int pages = Math.max(1, (rows.size() + size - 1) / size);
    page = Math.min(page, pages);
    int from = (page - 1) * size;
    var result = Map.<String, Object>of(
        "serverPid",
        ProcessHandle.current().pid(),
        "sampleTime",
        s.sampleTime(),
        "uptimeMillis",
        s.uptimeMillis(),
        "httpPort",
        s.httpPort(),
        "socksPort",
        s.socksPort(),
        "upstream",
        s.upstream(),
        "processStatus",
        s.processStatus(),
        "summary",
        s.summary(),
        "pagination",
        Map.of("page", page, "pageSize", size, "total", rows.size(), "pages", pages),
        "connections",
        rows.subList(from, Math.min(from + size, rows.size())));
    if (!value(q, "traffic", "").equals("1")) return result;
    var withTraffic = new LinkedHashMap<>(result);
    withTraffic.put("trafficHistory", s.trafficHistory());
    return withTraffic;
  }

  static void respond(ChannelHandlerContext ctx, int code, String type, byte[] body) {
    FullHttpResponse response =
        new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(code),
            io.netty.buffer.Unpooled.wrappedBuffer(body));
    response
        .headers()
        .set(HttpHeaderNames.CONTENT_TYPE, type + "; charset=utf-8")
        .setInt(HttpHeaderNames.CONTENT_LENGTH, body.length)
        .set(HttpHeaderNames.CACHE_CONTROL, "no-store")
        .set("X-Content-Type-Options", "nosniff")
        .set(
            "Content-Security-Policy",
            "default-src 'self'; style-src 'self'; script-src 'self'; frame-ancestors 'none'");
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

  public void close() {
    if (server != null) server.close().syncUninterruptibly();
    if (monitor != null) monitor.close();
    requests.shutdownNow();
    group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
  }
}
