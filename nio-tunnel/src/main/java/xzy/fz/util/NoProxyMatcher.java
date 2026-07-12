package xzy.fz.util;

import java.util.Arrays;
import java.util.List;

/**
 * Matches hostnames and IP addresses against no-proxy patterns.
 * <p>
 * Supports the following pattern types:
 * <ul>
 *   <li><b>Exact host/IP</b> - e.g., {@code maven.aliyun.com}, {@code 10.240.22.152}</li>
 *   <li><b>Wildcard domain prefix</b> - e.g., {@code *.tencent.com} (matches {@code foo.tencent.com} and {@code tencent.com})</li>
 *   <li><b>IP prefix wildcard</b> - e.g., {@code 192.168.*} (matches any IP starting with {@code 192.168.})</li>
 * </ul>
 * <p>
 * All matching is case-insensitive.
 * Patterns are parsed from a comma-separated string (e.g., from {@code no.proxy.hosts} config property).
 */
public final class NoProxyMatcher {
    private final List<String> patterns;

    /**
     * Creates a matcher from a comma-separated list of no-proxy patterns.
     *
     * @param noProxyHosts Comma-separated patterns (may be null or blank for empty matcher)
     */
    public NoProxyMatcher(String noProxyHosts) {
        if (noProxyHosts == null || noProxyHosts.isBlank()) {
            this.patterns = List.of();
        } else {
            this.patterns = Arrays.stream(noProxyHosts.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(String::toLowerCase)
                    .toList();
        }
    }

    /**
     * Returns true if the given host should bypass the upstream proxy (connect directly).
     *
     * @param host Hostname or IP address to check
     * @return {@code true} if the host matches any no-proxy pattern
     */
    public boolean matches(String host) {
        if (host == null || host.isBlank() || patterns.isEmpty()) {
            return false;
        }
        String lowerHost = host.toLowerCase();
        for (String pattern : patterns) {
            if (matchesPattern(lowerHost, pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if there are no patterns configured (matcher is effectively disabled).
     */
    public boolean isEmpty() {
        return patterns.isEmpty();
    }

    private boolean matchesPattern(String host, String pattern) {
        if (pattern.startsWith("*.")) {
            // Wildcard domain: *.foo.com matches foo.com and sub.foo.com
            String suffix = pattern.substring(2);
            return host.equals(suffix) || host.endsWith("." + suffix);
        } else if (pattern.endsWith(".*")) {
            // IP prefix wildcard: 192.168.* matches 192.168.1.1
            String prefix = pattern.substring(0, pattern.length() - 2);
            return host.startsWith(prefix + ".");
        } else {
            // Exact match
            return host.equals(pattern);
        }
    }
}
