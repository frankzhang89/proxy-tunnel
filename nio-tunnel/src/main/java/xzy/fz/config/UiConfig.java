package xzy.fz.config;

public record UiConfig(
    boolean enabled,
    String host,
    int port,
    int historySeconds,
    int historyMaxEntries,
    boolean processLookupEnabled,
    int processNameCacheSeconds) {
  public UiConfig {
    if (processNameCacheSeconds < 0)
      throw new IllegalArgumentException("ui.process.name.cache.seconds must be nonnegative");
  }

  public UiConfig(
      boolean enabled,
      String host,
      int port,
      int historySeconds,
      int historyMaxEntries,
      boolean processLookupEnabled) {
    this(enabled, host, port, historySeconds, historyMaxEntries, processLookupEnabled, 300);
  }

  public static UiConfig defaults() {
    return new UiConfig(true, "127.0.0.1", 8390, 300, 1000, true);
  }
}
