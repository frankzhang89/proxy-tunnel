package xzy.fz.monitor;

import static org.junit.jupiter.api.Assertions.*;

import com.google.common.base.Ticker;
import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import xzy.fz.config.*;

class ProcessNameCacheTest {
  static ProcessHandle identity(long pid) {
    return (ProcessHandle)
        Proxy.newProxyInstance(
            ProcessHandle.class.getClassLoader(),
            new Class<?>[] {ProcessHandle.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "equals" -> proxy == args[0];
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "pid" -> pid;
                  default -> throw new UnsupportedOperationException();
                });
  }

  static class Clock extends Ticker {
    long nanos;

    public long read() {
      return nanos;
    }

    void advance(long seconds) {
      nanos += TimeUnit.SECONDS.toNanos(seconds);
    }
  }

  @Test
  void hitsDoNotExtendExpiryAndRefreshIsOnDemand() {
    Clock clock = new Clock();
    AtomicInteger reads = new AtomicInteger();
    ProcessHandle process = identity(42);
    try (var cache =
        new ProcessNameCache(300, clock, pid -> process, h -> "name-" + reads.incrementAndGet())) {
      assertEquals("name-1", cache.resolve(42, null));
      clock.advance(299);
      assertEquals("name-1", cache.resolve(42, null));
      assertEquals(1, reads.get());
      clock.advance(1);
      assertEquals(1, reads.get());
      assertEquals("name-2", cache.resolve(42, null));
      assertEquals(2, reads.get());
    }
  }

  @Test
  void pidReuseAndDifferentPidsDoNotReuseNames() {
    var current = new AtomicReference<>(identity(42));
    AtomicInteger reads = new AtomicInteger();
    try (var cache =
        new ProcessNameCache(
            300, new Clock(), pid -> current.get(), h -> "name-" + reads.incrementAndGet())) {
      assertEquals("name-1", cache.resolve(42, null));
      assertEquals("name-2", cache.resolve(43, null));
      current.set(identity(42));
      assertEquals("name-3", cache.resolve(42, null));
      assertEquals(3, reads.get());
    }
  }

  @Test
  void emptyAndFailedNamesAreNotCachedAndLinuxNamesArePreferred() {
    AtomicInteger reads = new AtomicInteger();
    ProcessHandle process = identity(1);
    try (var cache =
        new ProcessNameCache(
            300,
            new Clock(),
            pid -> process,
            h -> {
              int n = reads.incrementAndGet();
              if (n == 1) return null;
              if (n == 2) throw new SecurityException();
              return "java";
            })) {
      assertNull(cache.resolve(1, null));
      assertEquals(0, cache.size());
      assertNull(cache.resolve(1, null));
      assertEquals(0, cache.size());
      assertEquals("java", cache.resolve(1, null));
      assertEquals(3, reads.get());
      assertEquals("curl", cache.resolve(2, "curl"));
      assertEquals("curl", cache.resolve(2, "renamed"));
      assertEquals(3, reads.get());
    }
    try (var cache = new ProcessNameCache(300, new Clock(), pid -> null, h -> "wrong")) {
      assertEquals("ss-name", cache.resolve(3, "ss-name"));
      assertEquals(0, cache.size());
    }
  }

  @Test
  void disabledCacheLoadsEveryTimeAndCloseClearsEntries() {
    ProcessHandle process = identity(1);
    AtomicInteger reads = new AtomicInteger();
    var cache =
        new ProcessNameCache(
            0, new Clock(), pid -> process, h -> "name-" + reads.incrementAndGet());
    assertEquals("name-1", cache.resolve(1, null));
    assertEquals("name-2", cache.resolve(1, null));
    assertEquals(0, cache.size());
    cache.close();
    assertNull(cache.resolve(1, null));
    var enabled = new ProcessNameCache(300, new Clock(), pid -> process, h -> "name");
    enabled.resolve(1, null);
    assertEquals(1, enabled.size());
    enabled.close();
    assertEquals(0, enabled.size());
  }

  @Test
  void capacityIsBounded() {
    try (var cache =
        new ProcessNameCache(300, new Clock(), ProcessNameCacheTest::identity, h -> "name")) {
      for (long pid = 0; pid < 4200; pid++) cache.resolve(pid, null);
      assertTrue(cache.size() <= 4096);
      assertTrue(cache.size() > 0);
    }
  }

  @Test
  void configurationDefaultsOverridesAndValidation() {
    assertEquals(300, UiConfig.defaults().processNameCacheSeconds());
    assertEquals(
        300, new UiConfig(true, "127.0.0.1", 8390, 300, 1000, true).processNameCacheSeconds());
    assertEquals(
        17,
        ConfigLoader.load(Cli.parse(new String[] {"--ui.process.name.cache.seconds=17"}))
            .ui()
            .processNameCacheSeconds());
    assertEquals(
        0,
        ConfigLoader.load(Cli.parse(new String[] {"--ui.process.name.cache.seconds=0"}))
            .ui()
            .processNameCacheSeconds());
    assertThrows(
        IllegalArgumentException.class,
        () -> ConfigLoader.load(Cli.parse(new String[] {"--ui.process.name.cache.seconds=-1"})));
  }
}
