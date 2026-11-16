package xzy.fz.monitor;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.util.AttributeKey;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;
import xzy.fz.config.Config;

/** Runtime-only telemetry. No payloads, credentials or request paths are retained. */
public final class Monitor implements AutoCloseable {
  public static final AttributeKey<Monitor> OWNER = AttributeKey.valueOf("monitor.owner");
  public static final AttributeKey<Client> CLIENT = AttributeKey.valueOf("monitor.client");
  public static final AttributeKey<Flow> FLOW = AttributeKey.valueOf("monitor.flow");
  private final Config config;
  private final Set<String> localSources = new HashSet<>();
  private final LongSupplier nanoClock, wallClock;
  private final ConcurrentMap<String, Flow> flows = new ConcurrentHashMap<>();
  private final AtomicLong sequence = new AtomicLong(),
      up = new AtomicLong(),
      down = new AtomicLong();
  private final long started;
  private long previousNano, previousUp, previousDown;
  private final ScheduledExecutorService sampler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "monitor-sampler");
            t.setDaemon(true);
            return t;
          });
  private ProcessResolver resolver;
  private volatile Snapshot snapshot;

  public Monitor(Config config) {
    this(config, System::nanoTime, System::currentTimeMillis);
  }

  Monitor(Config config, LongSupplier nanoClock, LongSupplier wallClock) {
    this.config = config;
    try {
      for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces()))
        for (InetAddress a : Collections.list(n.getInetAddresses()))
          localSources.add(ProcessResolver.normalize(a.getHostAddress()));
    } catch (SocketException ignored) {
      /* Loopback remains identifiable without interface enumeration. */
    }

    this.nanoClock = nanoClock;
    this.wallClock = wallClock;
    started = wallClock.getAsLong();
    previousNano = nanoClock.getAsLong();
    sample();
  }

  public void start() {
    if (config.ui().processLookupEnabled())
      resolver = new ProcessResolver(config.ui().processNameCacheSeconds());
    sampler.scheduleAtFixedRate(
        () -> {
          try {
            sample();
          } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(Monitor.class).warn("Monitor sample failed", e);
          }
        },
        1,
        1,
        TimeUnit.SECONDS);
  }

  public void accept(Channel channel) {
    channel.attr(OWNER).set(this);
    Client c =
        new Client(
            Long.toString(sequence.incrementAndGet()),
            endpoint(channel.remoteAddress()),
            endpoint(channel.localAddress()),
            channel);
    channel.attr(CLIENT).set(c);
    channel
        .closeFuture()
        .addListener(
            ignored -> {
              for (Flow f : c.activeFlows) f.finish(null);
            });
    boolean local = localSources.contains(ProcessResolver.normalize(c.source.host()));
    try {
      local |= InetAddress.getByName(c.source.host()).isLoopbackAddress();
    } catch (UnknownHostException ignored) {
    }
    if (!local)
      c.process =
          new ProcessInfo(null, null, "remote", "Process lookup is unavailable for remote clients");
    else if (resolver != null) resolver.submit(c);
    else c.process = new ProcessInfo(null, null, "unavailable", "Process lookup is disabled");
  }

  public static Flow begin(
      ChannelHandlerContext ctx,
      String host,
      int port,
      String protocol,
      boolean direct,
      Config config) {
    Monitor m = ctx.channel().attr(OWNER).get();
    if (m == null) return null;
    Client c = ctx.channel().attr(CLIENT).get();
    Flow f =
        new Flow(
            m,
            Long.toString(m.sequence.incrementAndGet()),
            c,
            host,
            port,
            protocol,
            direct ? "direct" : "upstream",
            direct ? null : config.upstreamHost(),
            direct ? null : config.upstreamPort());
    c.activeFlows.add(f);
    m.flows.put(f.id, f);
    if (!c.channel.isActive()) f.finish(null);
    return f;
  }

  public static void attach(Channel channel, Flow flow) {
    if (flow == null) return;
    channel.attr(FLOW).set(flow);
    channel.pipeline().addFirst("monitor-bytes", new Bytes(flow));
    channel.closeFuture().addListener(f -> flow.finish(null));
  }

  public static void active(Channel channel) {
    Flow f = channel.attr(FLOW).get();
    if (f != null) f.active();
  }

  public static void protocol(Channel channel, String protocol) {
    Flow f = channel.attr(FLOW).get();
    if (f != null) f.protocol = protocol;
  }

  public static void failed(Channel channel, Throwable cause) {
    failed(channel, cause == null ? "Connection failed" : cause.getClass().getSimpleName());
  }

  public static void failed(Channel channel, String reason) {
    Flow f = channel.attr(FLOW).get();
    if (f != null) f.finish(reason);
  }

  static Endpoint endpoint(SocketAddress address) {
    if (address instanceof InetSocketAddress a)
      return new Endpoint(a.getAddress().getHostAddress(), a.getPort());
    return new Endpoint("unknown", 0);
  }

  public synchronized void sample() {
    long now = nanoClock.getAsLong(), wall = wallClock.getAsLong();
    double seconds = Math.max(1, now - previousNano) / 1e9;
    List<Flow> ended =
        flows.values().stream()
            .filter(f -> f.endedAt != null)
            .sorted(Comparator.comparingLong((Flow f) -> f.endedAt).reversed())
            .toList();
    for (int i = 0; i < ended.size(); i++) {
      Flow f = ended.get(i);
      if (i >= Math.max(0, config.ui().historyMaxEntries())
          || wall - f.endedAt >= Math.max(0L, config.ui().historySeconds()) * 1000)
        flows.remove(f.id, f);
    }
    List<Connection> rows = new ArrayList<>();
    Set<String> clients = new HashSet<>();
    int active = 0, direct = 0, upstream = 0;
    for (Flow f : flows.values()) {
      long u = f.up.get(), d = f.down.get();
      boolean live = f.endedAt == null;
      if (live) {
        active++;
        clients.add(f.client.id);
        if (f.route.equals("direct")) direct++;
        else upstream++;
      }
      rows.add(
          new Connection(
              f.id,
              f.client.id,
              f.client.source,
              f.client.listener,
              f.client.process,
              f.targetHost,
              f.targetPort,
              f.protocol,
              f.route,
              f.upstreamHost,
              f.upstreamPort,
              f.state,
              f.startedAt,
              f.endedAt,
              f.reason,
              u,
              d,
              live ? Math.max(0, u - f.previousUp) / seconds : 0,
              live ? Math.max(0, d - f.previousDown) / seconds : 0));
      f.previousUp = u;
      f.previousDown = d;
    }
    long totalUp = up.get(), totalDown = down.get();
    snapshot =
        new Snapshot(
            wall,
            Math.max(0, wall - started),
            config.listenPort(),
            config.socksPort(),
            new Endpoint(config.upstreamHost(), config.upstreamPort()),
            resolver == null ? "disabled" : resolver.status(),
            new Summary(
                clients.size(),
                active,
                rows.size() - active,
                direct,
                upstream,
                totalUp,
                totalDown,
                Math.max(0, totalUp - previousUp) / seconds,
                Math.max(0, totalDown - previousDown) / seconds),
            List.copyOf(rows));
    previousNano = now;
    previousUp = totalUp;
    previousDown = totalDown;
  }

  public Snapshot snapshot() {
    return snapshot;
  }

  @Override
  public void close() {
    sampler.shutdownNow();
    if (resolver != null) resolver.close();
    flows.clear();
  }

  public record Endpoint(String host, int port) {}

  public record ProcessInfo(Long pid, String name, String status, String reason) {}

  public static final class Client {
    final String id;
    final Endpoint source, listener;
    final Channel channel;
    final Set<Flow> activeFlows = ConcurrentHashMap.newKeySet();
    volatile ProcessInfo process = new ProcessInfo(null, null, "pending", "Resolving");

    Client(String id, Endpoint source, Endpoint listener, Channel channel) {
      this.id = id;
      this.source = source;
      this.listener = listener;
      this.channel = channel;
    }
  }

  public static final class Flow {
    final Monitor owner;
    final String id;
    final Client client;
    final String targetHost, route, upstreamHost;
    final int targetPort;
    final Integer upstreamPort;
    final long startedAt;
    final AtomicLong up = new AtomicLong(), down = new AtomicLong();
    volatile String protocol, state = "connecting", reason;
    volatile Long endedAt;
    long previousUp, previousDown;

    Flow(
        Monitor owner,
        String id,
        Client client,
        String host,
        int port,
        String protocol,
        String route,
        String upstreamHost,
        Integer upstreamPort) {
      this.owner = owner;
      this.id = id;
      this.client = client;
      targetHost = host;
      targetPort = port;
      this.protocol = protocol;
      this.route = route;
      this.upstreamHost = upstreamHost;
      this.upstreamPort = upstreamPort;
      startedAt = owner.wallClock.getAsLong();
    }

    synchronized void active() {
      if (endedAt == null) state = "active";
    }

    synchronized void finish(String failure) {
      if (endedAt != null) return;
      reason =
          failure != null
              ? failure
              : state.equals("connecting") ? "Connection closed before establishment" : null;
      state = reason == null ? "closed" : "failed";
      endedAt = owner.wallClock.getAsLong();
      client.activeFlows.remove(this);
    }
  }

  static final class Bytes extends ChannelDuplexHandler {
    final Flow flow;

    Bytes(Flow flow) {
      this.flow = flow;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      if (msg instanceof ByteBuf b) {
        int n = b.readableBytes();
        flow.down.addAndGet(n);
        flow.owner.down.addAndGet(n);
      }
      ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
      int n = msg instanceof ByteBuf b ? b.readableBytes() : 0;
      ChannelPromise tracked = promise.unvoid();
      tracked.addListener(
          f -> {
            if (f.isSuccess()) {
              flow.up.addAndGet(n);
              flow.owner.up.addAndGet(n);
            } else flow.finish("Write failed");
          });
      ctx.write(msg, tracked);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      flow.finish(cause.getClass().getSimpleName());
      ctx.fireExceptionCaught(cause);
    }
  }

  public record Connection(
      String id,
      String clientId,
      Endpoint source,
      Endpoint listener,
      ProcessInfo process,
      String targetHost,
      int targetPort,
      String protocol,
      String route,
      String upstreamHost,
      Integer upstreamPort,
      String state,
      long startedAt,
      Long endedAt,
      String reason,
      long uploadBytes,
      long downloadBytes,
      double uploadBps,
      double downloadBps) {}

  public record Summary(
      int activeClients,
      int activeFlows,
      int recentFlows,
      int directFlows,
      int upstreamFlows,
      long uploadBytes,
      long downloadBytes,
      double uploadBps,
      double downloadBps) {}

  public record Snapshot(
      long sampleTime,
      long uptimeMillis,
      int httpPort,
      int socksPort,
      Endpoint upstream,
      String processStatus,
      Summary summary,
      List<Connection> connections) {}
}
