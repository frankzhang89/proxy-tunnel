package xzy.fz.monitor;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongFunction;

/** Shared successful-name cache. PID reuse is checked independently of name expiry. */
final class ProcessNameCache implements AutoCloseable {
  private record Entry(ProcessHandle identity, String name) {}

  private final Cache<Long, Entry> cache;
  private final LongFunction<ProcessHandle> identityReader;
  private final Function<ProcessHandle, String> nameReader;
  private final boolean enabled;
  private boolean closed;

  ProcessNameCache(int seconds) {
    this(
        seconds,
        Ticker.systemTicker(),
        pid -> ProcessHandle.of(pid).orElse(null),
        handle ->
            handle
                .info()
                .command()
                .map(command -> Path.of(command).getFileName().toString())
                .orElse(null));
  }

  ProcessNameCache(
      int seconds,
      Ticker ticker,
      LongFunction<ProcessHandle> identityReader,
      Function<ProcessHandle, String> nameReader) {
    if (seconds < 0) throw new IllegalArgumentException("Name cache TTL must be nonnegative");
    enabled = seconds > 0;
    cache =
        CacheBuilder.newBuilder()
            .maximumSize(4096)
            .expireAfterWrite(seconds, TimeUnit.SECONDS)
            .ticker(ticker)
            .build();
    this.identityReader = identityReader;
    this.nameReader = nameReader;
  }

  synchronized String resolve(long pid, String suppliedName) {
    if (closed) return null;
    ProcessHandle identity;
    try {
      identity = identityReader.apply(pid);
    } catch (RuntimeException e) {
      identity = null;
    }
    Entry cached = enabled ? cache.getIfPresent(pid) : null;
    if (cached != null && identity != null && cached.identity.equals(identity)) return cached.name;
    cache.invalidate(pid);
    String name = suppliedName;
    if (name == null || name.isBlank()) {
      try {
        name = identity == null ? null : nameReader.apply(identity);
      } catch (RuntimeException e) {
        name = null;
      }
    }
    if (name == null || name.isBlank()) return null;
    if (enabled && identity != null) cache.put(pid, new Entry(identity, name));
    return name;
  }

  synchronized long size() {
    cache.cleanUp();
    return cache.size();
  }

  @Override
  public synchronized void close() {
    closed = true;
    cache.invalidateAll();
    cache.cleanUp();
  }
}
