package xzy.fz.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Bidirectional relay handler for tunneled connections.
 * <p>
 * This handler is used after a tunnel is established (either via HTTP CONNECT
 * or SOCKS5 CONNECT) to relay raw bytes between the client and upstream channels.
 * <p>
 * Each RelayHandler is paired with a target channel. When bytes are received,
 * they are forwarded to the paired channel. When either channel closes, the
 * other is also closed.
 *
 * <h2>Usage:</h2>
 * <pre>
 * // After tunnel is established:
 * clientChannel.pipeline().addLast(new RelayHandler(upstreamChannel));
 * upstreamChannel.pipeline().addLast(new RelayHandler(clientChannel));
 * </pre>
 *
 * <h2>Features:</h2>
 * <ul>
 *   <li>Zero-copy forwarding of ByteBuf data</li>
 *   <li>Automatic cleanup when either channel closes</li>
 *   <li>Backpressure handling via Netty's write futures</li>
 *   <li>Bytes transferred tracking for access logging</li>
 * </ul>
 */
public class RelayHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(RelayHandler.class);

    /** The channel to relay bytes to */
    private final Channel relayChannel;

    /** Callback to execute when relay completes (connection closed) */
    private final Runnable onCompleteCallback;

    /** Total bytes transferred through this relay */
    private final AtomicLong bytesTransferred = new AtomicLong(0);

    /** Whether the completion callback has been executed */
    private volatile boolean callbackExecuted = false;

    /**
     * Creates a relay handler that forwards bytes to the specified channel.
     *
     * @param relayChannel Target channel to forward bytes to
     */
    public RelayHandler(Channel relayChannel) {
        this(relayChannel, null);
    }

    /**
     * Creates a relay handler with a completion callback.
     *
     * @param relayChannel       Target channel to forward bytes to
     * @param onCompleteCallback Callback to execute when relay completes
     */
    public RelayHandler(Channel relayChannel, Runnable onCompleteCallback) {
        this.relayChannel = relayChannel;
        this.onCompleteCallback = onCompleteCallback;
    }

    /**
     * Gets the total bytes transferred through this relay.
     *
     * @return Total bytes transferred
     */
    public long getBytesTransferred() {
        return bytesTransferred.get();
    }

    /**
     * Forwards received bytes to the paired channel.
     * <p>
     * If the relay channel is active, writes and flushes the message.
     * If the relay channel is closed, releases the message buffer and closes this channel.
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (relayChannel.isActive()) {
            // Track bytes for access logging
            if (msg instanceof ByteBuf buf) {
                bytesTransferred.addAndGet(buf.readableBytes());
            }

            // Forward bytes to paired channel
            // Note: writeAndFlush transfers ownership of the ByteBuf to the channel
            relayChannel.writeAndFlush(msg).addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    // Write failed, close the connection
                    log.debug("Relay write failed, closing connection");
                    future.channel().close();
                }
            });
        } else {
            // Paired channel is closed, release buffer to prevent memory leak
            ReferenceCountUtil.release(msg);
            ctx.close();
        }
    }

    /**
     * Called when this channel becomes inactive (closed).
     * Closes the paired channel to complete the tunnel teardown.
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // Execute callback when relay completes
        executeCallback();

        if (relayChannel.isActive()) {
            log.debug("Channel inactive, closing paired channel");
            relayChannel.close();
        }
    }

    /**
     * Handles exceptions by closing both channels.
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("Relay error: {}", cause.getMessage());
        executeCallback();
        ctx.close();
        if (relayChannel.isActive()) {
            relayChannel.close();
        }
    }

    /**
     * Executes the completion callback if not already executed.
     */
    private void executeCallback() {
        if (onCompleteCallback != null && !callbackExecuted) {
            callbackExecuted = true;
            try {
                onCompleteCallback.run();
            } catch (Exception e) {
                log.debug("Error executing relay completion callback: {}", e.getMessage());
            }
        }
    }
}
