package xzy.fz;

import xzy.fz.config.CliArguments;
import xzy.fz.config.ConfigLoader;
import xzy.fz.config.TunnelConfig;
import xzy.fz.logging.SquidLogger;
import xzy.fz.server.ProxyApplication;

public final class NIOProxyTunnel {
    private NIOProxyTunnel() {
    }

    public static void main(String[] args) {
        CliArguments cli = CliArguments.parse(args);
        if (cli.helpRequested()) {
            CliArguments.printHelp();
            return;
        }

        TunnelConfig config = ConfigLoader.load(cli);
        SquidLogger logger = SquidLogger.create(config);
        ProxyApplication application = new ProxyApplication(config, logger);

        Runtime.getRuntime().addShutdownHook(new Thread(application::stop, "nio-tunnel-shutdown"));

        application.start();
    }
}
