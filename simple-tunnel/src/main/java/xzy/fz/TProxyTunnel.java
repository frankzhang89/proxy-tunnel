package xzy.fz;

import javax.net.ssl.SSLSocketFactory;
import java.net.ServerSocket;
import java.net.Socket;

public class TProxyTunnel {

    private static final int LISTEN_PORT = 3128;
    private static final String TARGET_HOST = "";
    private static final int TARGET_PORT = 811;

    public static void main(String[] args) throws Exception {
        try (var server = new ServerSocket(LISTEN_PORT)) {
            while (true) {
                var client = server.accept();
                Thread.startVirtualThread(() -> handle(client));
            }
        }
    }

    private static void handle(Socket a) {
        try (var b = SSLSocketFactory.getDefault().createSocket(TARGET_HOST, TARGET_PORT); a) {
            var t = Thread.startVirtualThread(() -> transfer(a, b));
            transfer(b, a);
            t.join();
        } catch (Exception ignored) {}
    }

    private static void transfer(Socket a, Socket b) {
        try (var in = a.getInputStream(); var out = b.getOutputStream()) {
            in.transferTo(out);
        } catch (Exception ignored) {}
    }
}
