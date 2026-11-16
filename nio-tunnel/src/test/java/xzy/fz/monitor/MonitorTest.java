package xzy.fz.monitor;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import xzy.fz.config.*;
import xzy.fz.util.NoProxyMatcher;

class MonitorTest {
  final List<EmbeddedChannel> allocated = new ArrayList<>();

  EmbeddedChannel channel(ChannelHandler... handlers) {
    var c = new EmbeddedChannel(handlers);
    allocated.add(c);
    return c;
  }

  @org.junit.jupiter.api.AfterEach
  void cleanup() {
    for (var c : allocated) c.finishAndReleaseAll();
  }

  static Config config(int port, boolean lookup) {
    return new Config(
        "127.0.0.1",
        8380,
        1080,
        false,
        null,
        "proxy.test",
        443,
        false,
        null,
        1000,
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
        new NoProxyMatcher(""),
        new UiConfig(true, "127.0.0.1", port, 300, 1000, lookup));
  }

  @Test
  void elapsedRatesIdleHistoryAndGlobalTotals() {
    AtomicLong nano = new AtomicLong(), wall = new AtomicLong(1000);
    try (Monitor m = new Monitor(config(8390, false), nano::get, wall::get)) {
      EmbeddedChannel c = channel(new ChannelInboundHandlerAdapter());
      EmbeddedChannel out = channel();
      m.accept(c);
      var f =
          Monitor.begin(
              c.pipeline().firstContext(),
              "example.test",
              443,
              "http_connect",
              false,
              config(8390, false));
      Monitor.attach(out, f);
      Monitor.active(out);
      out.writeOutbound(Unpooled.wrappedBuffer(new byte[200]));
      ((ByteBuf) out.readOutbound()).release();
      out.writeInbound(Unpooled.wrappedBuffer(new byte[100]));
      ((ByteBuf) out.readInbound()).release();
      nano.set(2_000_000_000L);
      m.sample();
      assertEquals(100, m.snapshot().summary().uploadBps());
      assertEquals(50, m.snapshot().connections().getFirst().downloadBps());
      nano.addAndGet(1_000_000_000L);
      m.sample();
      assertEquals(0, m.snapshot().summary().uploadBps());
      out.close();
      m.sample();
      assertEquals("closed", m.snapshot().connections().getFirst().state());
      assertEquals(0, m.snapshot().connections().getFirst().uploadBps());
      wall.addAndGet(300_001);
      m.sample();
      assertTrue(m.snapshot().connections().isEmpty());
      assertEquals(200, m.snapshot().summary().uploadBytes());
    }
  }

  @Test
  void failedWriteDoesNotCount() {
    try (Monitor m = new Monitor(config(8390, false))) {
      EmbeddedChannel c = channel(new ChannelInboundHandlerAdapter());
      EmbeddedChannel out =
          channel(
              new ChannelOutboundHandlerAdapter() {
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise p) {
                  io.netty.util.ReferenceCountUtil.release(msg);
                  p.setFailure(new IllegalStateException());
                }
              });
      m.accept(c);
      var f =
          Monitor.begin(c.pipeline().firstContext(), "x", 80, "http", true, config(8390, false));
      Monitor.attach(out, f);
      out.pipeline().remove("monitor-bytes");
      out.pipeline().addLast("monitor-bytes", new Monitor.Bytes(f));
      assertThrows(Exception.class, () -> out.writeOutbound(Unpooled.wrappedBuffer(new byte[20])));
      Monitor.active(out);
      out.close();
      m.sample();
      var r = m.snapshot().connections().getFirst();
      assertEquals("failed", r.state());
      assertEquals(0, r.uploadBytes());
      assertNull(r.upstreamHost());
    }
  }

  @Test
  void linuxParserAndMappedAddresses() {
    var rows =
        ProcessResolver.parseLinux(
            "ESTAB 0 0 127.0.0.1:1234 127.0.0.1:8380 users:((\"java\",pid=42,fd=8))\n"
                + "ESTAB 0 0 [::1]:1235 [::1]:1080"
                + " users:((\"curl\",pid=43,fd=3),(\"curl\",pid=43,fd=4))\n"
                + "ESTAB 0 0 127.0.0.1:1236 127.0.0.1:1080");
    assertEquals(2, rows.size());
    assertEquals(42, rows.getFirst().pid());
    assertEquals(1080, rows.getLast().remote().port());
    assertTrue(
        ProcessResolver.same(
            new Monitor.Endpoint("::ffff:127.0.0.1", 1), new Monitor.Endpoint("127.0.0.1", 1)));
  }

  @Test
  void filtersLeaveGlobalSummaryUnchanged() {
    try (Monitor m = new Monitor(config(8390, false))) {
      EmbeddedChannel c = channel(new ChannelInboundHandlerAdapter());
      m.accept(c);
      Monitor.begin(c.pipeline().firstContext(), "one", 80, "http", true, config(8390, false));
      Monitor.begin(c.pipeline().firstContext(), "two", 443, "socks5", false, config(8390, false));
      m.sample();
      var result = MonitorServer.view(m.snapshot(), Map.of("route", List.of("direct")));
      assertEquals(1, ((List<?>) result.get("connections")).size());
      assertEquals(2, ((Monitor.Summary) result.get("summary")).activeFlows());
      assertThrows(
          IllegalArgumentException.class,
          () -> MonitorServer.view(m.snapshot(), Map.of("scope", List.of("bad"))));
    }
  }

  @Test
  void configuredUpstreamIsPresentWithoutTransfersAndWithDirectOnly() {
    Config original = config(8390, false);
    Config config =
        new Config(
            original.listenHost(),
            original.listenPort(),
            original.socksPort(),
            false,
            null,
            "2001:db8::1",
            8443,
            false,
            null,
            1000,
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
            new NoProxyMatcher(""),
            original.ui());
    try (Monitor m = new Monitor(config)) {
      var upstream = new Monitor.Endpoint("2001:db8::1", 8443);
      assertEquals(upstream, MonitorServer.view(m.snapshot(), Map.of()).get("upstream"));
      EmbeddedChannel c = channel(new ChannelInboundHandlerAdapter());
      m.accept(c);
      Monitor.begin(c.pipeline().firstContext(), "direct.test", 80, "http", true, config);
      m.sample();
      var result = MonitorServer.view(m.snapshot(), Map.of());
      assertEquals(upstream, result.get("upstream"));
      assertNull(m.snapshot().connections().getFirst().upstreamHost());
    }
  }
}
