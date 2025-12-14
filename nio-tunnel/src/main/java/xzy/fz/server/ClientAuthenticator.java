package xzy.fz.server;

import xzy.fz.config.TunnelConfig;

/**
 * Simple helper that validates optional Basic authentication headers on inbound proxies.
 */
public interface ClientAuthenticator {
    boolean authorize(String proxyAuthorizationHeader);

    static ClientAuthenticator basic(TunnelConfig config) {
        if (!config.requireClientAuth()) {
            return header -> true;
        }
        String expected = config.expectedClientAuthHeader();
        return header -> expected.equals(header);
    }
}

