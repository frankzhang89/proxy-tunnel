package xzy.fz.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class RelayHandlerBackpressureTest {
    /** Keep writes in the real outbound buffer until the test permits transport progress. */
    private static class SlowChannel extends EmbeddedChannel {
        int permits;
        boolean fail;

        @Override protected void doWrite(ChannelOutboundBuffer buffer) {
            while (buffer.current() != null && permits-- > 0) {
                if (fail) buffer.remove(new IOException("simulated transport failure"));
                else buffer.remove();
            }
        }

        void drain(int count) {
            permits = count;
            flush();
            runPendingTasks();
        }
    }

    private static void install(SlowChannel a, SlowChannel b) {
        a.pipeline().addLast(new RelayHandler(b));
        b.pipeline().addLast(new RelayHandler(a));
        RelayHandler.activatePair(a, b);
    }

    @Test void pausesAndResumesEachDirectionAcrossRealWatermarks() {
        SlowChannel a = new SlowChannel(), b = new SlowChannel();
        try {
            install(a, b);
            for (int cycle = 0; cycle < 5; cycle++) {
                for (SlowChannel source : new SlowChannel[]{a, b}) {
                    SlowChannel sink = source == a ? b : a;
                    ByteBuf[] chunks = new ByteBuf[3];
                    for (int i = 0; i < 3; i++) {
                        chunks[i] = Unpooled.buffer(24 * 1024).writeZero(24 * 1024);
                        source.writeInbound(chunks[i]);
                    }
                    assertFalse(sink.isWritable());
                    assertFalse(source.config().isAutoRead());
                    assertTrue(sink.config().isAutoRead(), "reverse direction must remain readable");
                    sink.drain(1);
                    assertFalse(source.config().isAutoRead(), "above low watermark");
                    sink.drain(1);
                    assertTrue(source.config().isAutoRead(), "below low watermark");
                    sink.drain(1);
                    for (ByteBuf chunk : chunks) assertEquals(0, chunk.refCnt());
                }
            }
        } finally { a.finishAndReleaseAll(); b.finishAndReleaseAll(); }
    }

    @Test void activationHandlesExistingCongestionAndInitiallyPausedReads() {
        SlowChannel a = new SlowChannel(), b = new SlowChannel();
        try {
            b.writeAndFlush(Unpooled.buffer(80 * 1024).writeZero(80 * 1024));
            assertFalse(b.isWritable());
            a.config().setAutoRead(false);
            b.config().setAutoRead(false);
            install(a, b);
            assertEquals(32 * 1024, b.config().getWriteBufferWaterMark().low());
            assertEquals(64 * 1024, a.config().getWriteBufferWaterMark().high());
            assertFalse(a.config().isAutoRead());
            assertTrue(b.config().isAutoRead());
            b.drain(1);
            assertTrue(a.config().isAutoRead());
        } finally { a.finishAndReleaseAll(); b.finishAndReleaseAll(); }
    }

    @Test void closingPausedPairReleasesPendingBytesAndLogsOnce() {
        SlowChannel a = new SlowChannel(), b = new SlowChannel();
        var callbacks = new java.util.concurrent.atomic.AtomicInteger();
        try {
            a.pipeline().addLast(new RelayHandler(b, callbacks::incrementAndGet));
            b.pipeline().addLast(new RelayHandler(a));
            RelayHandler.activatePair(a, b);
            ByteBuf body = Unpooled.buffer(80 * 1024).writeZero(80 * 1024);
            a.writeInbound(body);
            assertFalse(a.config().isAutoRead());
            a.close();
            assertFalse(b.isActive());
            assertEquals(0, body.refCnt());
            RelayHandler.activatePair(a, b);
            assertFalse(a.config().isAutoRead());
            assertEquals(1, callbacks.get());
        } finally { a.finishAndReleaseAll(); b.finishAndReleaseAll(); }
    }

    @Test void writeFailureClosesBothSidesAndReleasesMessage() {
        SlowChannel a = new SlowChannel(), b = new SlowChannel();
        try {
            install(a, b);
            ByteBuf body = Unpooled.buffer(10).writeZero(10);
            a.writeInbound(body);
            b.fail = true;
            b.drain(1);
            assertFalse(a.isActive());
            assertFalse(b.isActive());
            assertEquals(0, body.refCnt());
        } finally { a.finishAndReleaseAll(); b.finishAndReleaseAll(); }
    }
}
