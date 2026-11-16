package xzy.fz.monitor;

import com.sun.jna.Memory;
import com.sun.jna.platform.win32.IPHlpAPI;
import com.sun.jna.ptr.IntByReference;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Bounded, shared background lookup; never called by a proxy event loop. */
final class ProcessResolver implements AutoCloseable {
  record Owner(Monitor.Endpoint local, Monitor.Endpoint remote, long pid, String name) {}

  record Pending(Monitor.Client client, long deadline) {}

  private final Queue<Pending> pending = new ConcurrentLinkedQueue<>();
  private final ScheduledExecutorService worker =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "monitor-process-lookup");
            t.setDaemon(true);
            return t;
          });
  private final Set<String> localAddresses = new HashSet<>();
  private volatile String status = "ready";
  private volatile Process command;

  private final ProcessNameCache names;

  ProcessResolver(int cacheSeconds) {
    names = new ProcessNameCache(cacheSeconds);
    try {
      for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces()))
        for (InetAddress a : Collections.list(n.getInetAddresses()))
          localAddresses.add(normalize(a.getHostAddress()));
    } catch (SocketException e) {
      status = "Local interface lookup restricted";
    }
    worker.scheduleWithFixedDelay(this::lookup, 0, 500, TimeUnit.MILLISECONDS);
  }

  String status() {
    return status;
  }

  void submit(Monitor.Client c) {
    try {
      String address = normalize(c.source.host());
      if (!InetAddress.getByName(address).isLoopbackAddress()
          && !localAddresses.contains(address)) {
        c.process =
            new Monitor.ProcessInfo(
                null, null, "remote", "Process lookup is unavailable for remote clients");
        return;
      }
    } catch (Exception e) {
      unavailable(c, "Source address could not be identified");
      return;
    }
    if (pending.size() >= 1024) {
      unavailable(c, "Process lookup queue is full");
      return;
    }
    pending.add(new Pending(c, System.nanoTime() + TimeUnit.SECONDS.toNanos(2)));
  }

  static void unavailable(Monitor.Client c, String reason) {
    c.process = new Monitor.ProcessInfo(null, null, "unavailable", reason);
  }

  private void lookup() {
    List<Pending> batch = new ArrayList<>();
    Pending p;
    while ((p = pending.poll()) != null) {
      if (p.client.channel.isActive()) batch.add(p);
      else unavailable(p.client, "Connection has closed");
    }
    if (batch.isEmpty()) return;
    List<Owner> owners;
    try {
      String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
      if (os.contains("win")) owners = Windows.read();
      else if (os.contains("linux")) owners = linux();
      else
        throw new UnsupportedOperationException("Process lookup is not supported on this platform");
      status = "ready";
    } catch (Exception | LinkageError e) {
      status = "Process lookup unavailable (" + e.getClass().getSimpleName() + ")";
      org.slf4j.LoggerFactory.getLogger(ProcessResolver.class).debug("Process lookup failed", e);
      for (Pending item : batch) unavailable(item.client, status);
      return;
    }
    for (Pending item : batch) {
      Monitor.Client c = item.client;
      if (!c.channel.isActive()) {
        unavailable(c, "Connection has closed");
        continue;
      }
      List<Owner> matches =
          owners.stream()
              .filter(o -> same(o.local, c.source) && same(o.remote, c.listener))
              .toList();
      if (matches.size() == 1) {
        Owner o = matches.getFirst();
        String name = names.resolve(o.pid, o.name);
        c.process =
            new Monitor.ProcessInfo(
                o.pid, name, "resolved", name == null ? "Process name unavailable" : null);
      } else if (System.nanoTime() < item.deadline && matches.isEmpty()) pending.add(item);
      else
        unavailable(
            c,
            matches.size() > 1
                ? "Multiple processes own this connection"
                : "Process not found or insufficient permissions");
    }
  }

  static boolean same(Monitor.Endpoint a, Monitor.Endpoint b) {
    return a.port() == b.port() && normalize(a.host()).equals(normalize(b.host()));
  }

  static String normalize(String s) {
    s = s.replace("[", "").replace("]", "").replaceAll("%0$", "");
    try {
      return InetAddress.getByName(s).getHostAddress();
    } catch (Exception e) {
      return s;
    }
  }

  List<Owner> linux() throws Exception {
    ProcessBuilder builder = new ProcessBuilder("ss", "-H", "-n", "-t", "-p");
    builder.environment().put("LC_ALL", "C");
    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    Process child = builder.start();
    command = child;
    // Drain concurrently so a large socket table cannot fill the pipe and deadlock waitFor.
    CompletableFuture<String> output =
        CompletableFuture.supplyAsync(
            () -> {
              try (var in = child.getInputStream()) {
                return new String(in.readNBytes(8 * 1024 * 1024), StandardCharsets.UTF_8);
              } catch (Exception e) {
                throw new CompletionException(e);
              }
            });
    try {
      if (!child.waitFor(1000, TimeUnit.MILLISECONDS)) throw new TimeoutException("ss timeout");
      if (child.exitValue() != 0) throw new IllegalStateException("ss failed");
      return parseLinux(output.get(200, TimeUnit.MILLISECONDS));
    } finally {
      child.destroyForcibly();
      command = null;
    }
  }

  static List<Owner> parseLinux(String output) {
    List<Owner> result = new ArrayList<>();
    Pattern process = Pattern.compile("\\(\\\"([^\\\"]*)\\\",pid=(\\d+),");
    for (String line : output.split("\\R")) {
      String[] fields = line.trim().split("\\s+");
      if (fields.length < 5) continue;
      try {
        Monitor.Endpoint local = parseEndpoint(fields[3]), remote = parseEndpoint(fields[4]);
        Matcher match = process.matcher(line);
        Set<Long> seen = new HashSet<>();
        while (match.find()) {
          long pid = Long.parseLong(match.group(2));
          if (seen.add(pid)) result.add(new Owner(local, remote, pid, match.group(1)));
        }
      } catch (RuntimeException ignored) {
      }
    }
    return result;
  }

  static Monitor.Endpoint parseEndpoint(String value) {
    int split = value.lastIndexOf(':');
    return new Monitor.Endpoint(
        normalize(value.substring(0, split)), Integer.parseInt(value.substring(split + 1)));
  }

  private static class Windows {
    static int port(int raw) {
      return Short.toUnsignedInt(Short.reverseBytes((short) raw));
    }

    static String ipv4(int raw) throws UnknownHostException {
      return InetAddress.getByAddress(
              new byte[] {(byte) raw, (byte) (raw >>> 8), (byte) (raw >>> 16), (byte) (raw >>> 24)})
          .getHostAddress();
    }

    static List<Owner> read() throws Exception {
      List<Owner> result = new ArrayList<>();
      for (int family : new int[] {IPHlpAPI.AF_INET, IPHlpAPI.AF_INET6}) {
        IntByReference size = new IntByReference();
        IPHlpAPI api = IPHlpAPI.INSTANCE;
        int code = api.GetExtendedTcpTable(null, size, false, family, 5, 0);
        if (code != 0 && code != 122)
          throw new IllegalStateException(code == 5 ? "Access denied" : "TCP table error " + code);
        try (Memory memory = new Memory(Math.max(4, size.getValue()))) {
          code = api.GetExtendedTcpTable(memory, size, false, family, 5, 0);
          if (code != 0)
            throw new IllegalStateException(
                code == 5 ? "Access denied" : "TCP table error " + code);
          if (family == IPHlpAPI.AF_INET) {
            var table = new IPHlpAPI.MIB_TCPTABLE_OWNER_PID(memory);
            for (var row : table.table)
              result.add(
                  new Owner(
                      new Monitor.Endpoint(ipv4(row.dwLocalAddr), port(row.dwLocalPort)),
                      new Monitor.Endpoint(ipv4(row.dwRemoteAddr), port(row.dwRemotePort)),
                      Integer.toUnsignedLong(row.dwOwningPid),
                      null));
          } else {
            var table = new IPHlpAPI.MIB_TCP6TABLE_OWNER_PID(memory);
            for (var row : table.table)
              result.add(
                  new Owner(
                      new Monitor.Endpoint(
                          Inet6Address.getByAddress(null, row.LocalAddr, row.dwLocalScopeId)
                              .getHostAddress(),
                          port(row.dwLocalPort)),
                      new Monitor.Endpoint(
                          Inet6Address.getByAddress(null, row.RemoteAddr, row.dwRemoteScopeId)
                              .getHostAddress(),
                          port(row.dwRemotePort)),
                      Integer.toUnsignedLong(row.dwOwningPid),
                      null));
          }
        }
      }
      return result;
    }
  }

  @Override
  public void close() {
    worker.shutdownNow();
    names.close();
    Process child = command;
    if (child != null) child.destroyForcibly();
    Pending p;
    while ((p = pending.poll()) != null) unavailable(p.client, "Monitoring has stopped");
  }
}
