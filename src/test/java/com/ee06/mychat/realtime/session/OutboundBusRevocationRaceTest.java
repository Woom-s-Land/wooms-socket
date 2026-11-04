package com.ee06.mychat.realtime.session;

import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OutboundBusRevocationRaceTest {
    @Test void queuedPublicAndPrivateMessagesAreDroppedWhenRevokedBeforeWrite() {
        OutboundBus bus = new OutboundBus();
        EmbeddedChannel channel = new EmbeddedChannel(DefaultChannelId.newInstance());
        SocketSession session = bus.open(channel, "unused");
        bus.subscribe(session, "room", "/room/topic");
        bus.subscribe(session, "role", "/user/queue/private");
        bus.publish("/room/topic", "queued-public");
        bus.privateMessage(session.id, "/queue/private", "queued-private");
        synchronized (session) { session.closed = true; }
        channel.runPendingTasks();
        assertNull(channel.readOutbound());
        bus.close(session);
        channel.finishAndReleaseAll();
    }

    @Test void publishAndPrivateMessageCannotWriteAfterRevocationLinearizes() throws Exception {
        OutboundBus bus = new OutboundBus();
        EmbeddedChannel channel = new EmbeddedChannel(DefaultChannelId.newInstance());
        SocketSession session = bus.open(channel, "cookie-not-used");
        bus.subscribe(session, "room", "/room/topic");

        bus.subscribe(session, "role", "/user/queue/private");

        CountDownLatch attempting = new CountDownLatch(1);
        Thread writer;
        synchronized (session) {
            writer = new Thread(() -> {
                attempting.countDown();
                bus.publish("/room/topic", "late-public");
                bus.privateMessage(session.id, "/queue/private", "late-private");
            });
            writer.start();
            assertTrue(attempting.await(1, TimeUnit.SECONDS));
            session.closed = true; // same monitor and ordering point used by revocation
        }

        writer.join(1_000);
        assertFalse(writer.isAlive());
        channel.runPendingTasks();
        assertNull(channel.readOutbound());
        bus.close(session);
        channel.finishAndReleaseAll();
    }

    @Test void activeSessionStillReceivesTopicMessages() {
        OutboundBus bus = new OutboundBus();
        EmbeddedChannel channel = new EmbeddedChannel(DefaultChannelId.newInstance());
        SocketSession session = bus.open(channel, "cookie-not-used");
        bus.subscribe(session, "room", "/room/topic");

        bus.publish("/room/topic", "live");
        channel.runPendingTasks();
        TextWebSocketFrame frame = channel.readOutbound();
        assertNotNull(frame);
        assertTrue(frame.text().contains("\"body\":\"live\""));
        frame.release();
        bus.close(session);
        channel.finishAndReleaseAll();
    }
}
