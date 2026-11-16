package xzy.fz.monitor;

import java.net.InetAddress;
import java.util.*;
import java.util.function.Function;

/** Global ordering applied after filters and before pagination. Never sorts formatted units. */
final class ConnectionOrder {
  private ConnectionOrder() {}

  static Comparator<Monitor.Connection> comparator(String field, String order, String scope) {
    if (!Set.of("asc", "desc").contains(order)) throw new IllegalArgumentException("Invalid order");
    boolean descending = order.equals("desc");
    Comparator<Monitor.Connection> primary =
        switch (field) {
          case "" ->
              Comparator.comparingLong(
                      (Monitor.Connection r) ->
                          scope.equals("recent") && r.endedAt() != null
                              ? r.endedAt()
                              : r.startedAt())
                  .reversed();
          case "processName" ->
              ordered(r -> text(r.process().name()), Comparator.<String>naturalOrder(), descending);
          case "pid" ->
              ordered(r -> r.process().pid(), Comparator.<Long>naturalOrder(), descending);
          case "sourceIp" ->
              ordered(r -> address(r.source().host()), ConnectionOrder::compareAddress, descending);
          case "protocol" ->
              ordered(r -> protocol(r.protocol()), Comparator.<String>naturalOrder(), descending);
          case "target" ->
              ordered(r -> text(r.targetHost()), Comparator.<String>naturalOrder(), descending)
                  .thenComparing(
                      ordered(r -> r.targetPort(), Comparator.<Integer>naturalOrder(), descending));
          case "route" ->
              ordered(r -> text(r.route()), Comparator.<String>naturalOrder(), descending);
          case "uploadBps" ->
              ordered(r -> r.uploadBps(), Comparator.<Double>naturalOrder(), descending);
          case "downloadBps" ->
              ordered(r -> r.downloadBps(), Comparator.<Double>naturalOrder(), descending);
          case "status" ->
              ordered(
                  r ->
                      text(
                          switch (r.state()) {
                            case "connecting" -> "Connecting";
                            case "active" -> "Active";
                            case "closed" -> "Closed";
                            case "failed" -> "Failed";
                            default -> r.state();
                          }),
                  Comparator.<String>naturalOrder(),
                  descending);
          default -> throw new IllegalArgumentException("Invalid sort field");
        };
    return primary.thenComparingLong(r -> Long.parseLong(r.id()));
  }

  private static <T> Comparator<Monitor.Connection> ordered(
      Function<Monitor.Connection, T> key, Comparator<T> comparator, boolean descending) {
    return Comparator.comparing(
        key, Comparator.nullsLast(descending ? comparator.reversed() : comparator));
  }

  private static String text(String text) {
    return text == null || text.isBlank() ? null : text.toLowerCase(Locale.ROOT);
  }

  private static String protocol(String value) {
    if (value == null) return null;
    return text(
        switch (value) {
          case "http_connect" -> "HTTP CONNECT";
          case "socks4" -> "SOCKS4/4a";
          case "websocket" -> "WebSocket";
          default -> value;
        });
  }

  private static byte[] address(String value) {
    if (value == null) return null;
    String literal = value.replace("[", "").replace("]", "").split("%", 2)[0];
    // Only numeric literals reach InetAddress: sorting must never trigger DNS.
    if (!literal.matches("[0-9a-fA-F:.]+")
        || (!literal.contains(":") && !literal.matches("[0-9.]+"))) return null;
    try {
      return InetAddress.getByName(literal).getAddress();
    } catch (Exception ignored) {
      return null;
    }
  }

  private static int compareAddress(byte[] a, byte[] b) {
    int family = Integer.compare(a.length, b.length);
    return family == 0 ? Arrays.compareUnsigned(a, b) : family;
  }
}
