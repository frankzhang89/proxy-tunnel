package xzy.fz.monitor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ConnectionOrderTest {
  static Monitor.Connection row(
      String id,
      String name,
      Long pid,
      String ip,
      int port,
      String protocol,
      String target,
      int targetPort,
      String route,
      double up,
      double down,
      String status) {
    return new Monitor.Connection(
        id,
        id,
        new Monitor.Endpoint(ip, port),
        new Monitor.Endpoint("127.0.0.1", 8380),
        new Monitor.ProcessInfo(pid, name, name == null ? "unavailable" : "resolved", null),
        target,
        targetPort,
        protocol,
        route,
        route.equals("direct") ? null : "proxy.test",
        route.equals("direct") ? null : 443,
        status,
        Long.parseLong(id),
        status.equals("closed") ? 100L : null,
        null,
        100,
        200,
        up,
        down);
  }

  final List<Monitor.Connection> rows =
      List.of(
          row(
              "2",
              "Zulu",
              100L,
              "10.0.0.2",
              65535,
              "http",
              "same.test",
              443,
              "direct",
              1.001,
              90,
              "active"),
          row(
              "10",
              "alpha",
              9L,
              "10.0.0.10",
              50,
              "socks5",
              "Same.test",
              80,
              "upstream",
              1.002,
              2,
              "connecting"),
          row(
              "3",
              "ALPHA",
              null,
              "::1",
              5,
              "http_connect",
              "z.test",
              80,
              "upstream",
              0,
              0,
              "closed"),
          row(
              "4",
              null,
              10L,
              "::ffff:10.0.0.2",
              1,
              "websocket",
              "a.test",
              9999,
              "direct",
              1.001,
              90,
              "failed"));

  List<String> sorted(String field, String order) {
    return rows.stream()
        .sorted(ConnectionOrder.comparator(field, order, "all"))
        .map(Monitor.Connection::id)
        .toList();
  }

  @Test
  void allColumnsBothDirections() {
    assertEquals(List.of("3", "10", "2", "4"), sorted("processName", "asc"));
    assertEquals(List.of("2", "3", "10", "4"), sorted("processName", "desc"));
    assertEquals(List.of("10", "4", "2", "3"), sorted("pid", "asc"));
    assertEquals(List.of("2", "4", "10", "3"), sorted("pid", "desc"));
    assertEquals(List.of("2", "4", "10", "3"), sorted("sourceIp", "asc"));
    assertEquals(List.of("3", "10", "2", "4"), sorted("sourceIp", "desc"));
    assertEquals(List.of("2", "3", "10", "4"), sorted("protocol", "asc"));
    assertEquals(List.of("4", "10", "3", "2"), sorted("protocol", "desc"));
    assertEquals(List.of("4", "10", "2", "3"), sorted("target", "asc"));
    assertEquals(List.of("3", "2", "10", "4"), sorted("target", "desc"));
    assertEquals(List.of("2", "4", "3", "10"), sorted("route", "asc"));
    assertEquals(List.of("3", "10", "2", "4"), sorted("route", "desc"));
    assertEquals(List.of("3", "2", "4", "10"), sorted("uploadBps", "asc"));
    assertEquals(List.of("10", "2", "4", "3"), sorted("uploadBps", "desc"));
    assertEquals(List.of("3", "10", "2", "4"), sorted("downloadBps", "asc"));
    assertEquals(List.of("2", "4", "10", "3"), sorted("downloadBps", "desc"));
    assertEquals(List.of("2", "3", "10", "4"), sorted("status", "asc"));
    assertEquals(List.of("4", "10", "3", "2"), sorted("status", "desc"));
  }

  @Test
  void numericIpOrderAndUnknownLastWithoutDns() {
    var unknown =
        row("20", null, null, "unknown.example", 1, "http", "x", 80, "direct", 0, 0, "active");
    var ipv6 = row("21", null, null, "2001:db8::2", 1, "http", "x", 80, "direct", 0, 0, "active");
    var list = new ArrayList<>(rows);
    list.add(unknown);
    list.add(ipv6);
    assertEquals(
        List.of("2", "4", "10", "3", "21", "20"),
        list.stream()
            .sorted(ConnectionOrder.comparator("sourceIp", "asc", "all"))
            .map(Monitor.Connection::id)
            .toList());
    assertEquals(
        "20",
        list.stream()
            .sorted(ConnectionOrder.comparator("sourceIp", "desc", "all"))
            .toList()
            .getLast()
            .id());
  }

  Monitor.Snapshot snapshot(List<Monitor.Connection> rows) {
    return new Monitor.Snapshot(
        1000,
        500,
        8380,
        1080,
        new Monitor.Endpoint("proxy.test", 443),
        "ready",
        new Monitor.Summary(4, 4, 0, 2, 2, 1000, 2000, 1, 2),
        rows);
  }

  @SuppressWarnings("unchecked")
  List<Monitor.Connection> result(Map<String, List<String>> params, List<Monitor.Connection> data) {
    return (List<Monitor.Connection>) MonitorServer.view(snapshot(data), params).get("connections");
  }

  @Test
  void orderingPrecedesPagingAndSummaryIsUnchanged() {
    var q =
        Map.of(
            "scope",
            List.of("all"),
            "sort",
            List.of("pid"),
            "order",
            List.of("asc"),
            "pageSize",
            List.of("2"),
            "page",
            List.of("2"));
    assertEquals(List.of("2", "3"), result(q, rows).stream().map(Monitor.Connection::id).toList());
    assertEquals(snapshot(rows).summary(), MonitorServer.view(snapshot(rows), q).get("summary"));
    assertEquals(
        List.of("4"),
        result(
                Map.of(
                    "scope",
                    List.of("all"),
                    "sort",
                    List.of("pid"),
                    "route",
                    List.of("direct"),
                    "pageSize",
                    List.of("1")),
                rows)
            .stream()
            .map(Monitor.Connection::id)
            .toList());
  }

  @Test
  void pageSizesLastPageAndEmptyResults() {
    List<Monitor.Connection> many = new ArrayList<>();
    for (int i = 1; i <= 203; i++)
      many.add(
          row("" + i, "p", (long) i, "127.0.0.1", 1, "http", "x", 80, "direct", i, 0, "active"));
    for (int size : new int[] {25, 50, 100, 200}) {
      var q = Map.of("pageSize", List.of("" + size), "sort", List.of("pid"));
      assertEquals(size, result(q, many).size());
      assertEquals("1", result(q, many).getFirst().id());
      var last =
          MonitorServer.view(
              snapshot(many),
              Map.of(
                  "pageSize", List.of("" + size), "page", List.of("999"), "sort", List.of("pid")));
      assertEquals(203 % size, ((List<?>) last.get("connections")).size());
    }
    assertEquals(200, result(Map.of("pageSize", List.of("999")), many).size());
    var empty = MonitorServer.view(snapshot(List.of()), Map.of("page", List.of("999")));
    assertEquals(1, ((Map<?, ?>) empty.get("pagination")).get("page"));
    assertEquals(1, ((Map<?, ?>) empty.get("pagination")).get("pages"));
  }

  @Test
  void defaultOrderAndInvalidArguments() {
    assertEquals(List.of("10", "4", "3", "2"), sorted("", "asc"));
    assertThrows(
        IllegalArgumentException.class,
        () -> MonitorServer.view(snapshot(List.of()), Map.of("sort", List.of("bad"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> MonitorServer.view(snapshot(rows), Map.of("order", List.of("bad"))));
  }
}
