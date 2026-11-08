package com.ee06.mychat.realtime.websocket;

import com.ee06.mychat.realtime.backend.BackendBridge;
import com.ee06.mychat.realtime.config.SocketProperties;
import com.ee06.mychat.realtime.mafia.common.MafiaTimings;
import com.ee06.mychat.realtime.mafia.engine.MafiaBroadcaster;
import com.ee06.mychat.realtime.mafia.engine.MafiaGameEngine;
import com.ee06.mychat.realtime.mafia.repository.MafiaGameRepository;
import com.ee06.mychat.realtime.mafia.topic.TopicService;
import com.ee06.mychat.realtime.presence.PresenceService;
import com.ee06.mychat.realtime.room.RoomQueue;
import com.ee06.mychat.realtime.session.OutboundBus;
import com.ee06.mychat.realtime.session.SocketSession;
import com.ee06.mychat.realtime.sketch.SketchService;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeHubMafiaRoomTest {
    private static final String ROOM_A = "44444444-4444-4444-8444-444444444444";
    private static final String ROOM_B = "55555555-5555-4555-8555-555555555555";
    private static final String USER = "11111111-1111-4111-8111-111111111111";

    private final SocketProperties properties = new SocketProperties();
    private final RoomQueue queue = new RoomQueue(properties);
    private final MafiaGameRepository repository = new MafiaGameRepository(mock(TopicService.class));
    private final MafiaBroadcaster broadcaster = mock(MafiaBroadcaster.class);
    private final MafiaGameEngine engine = new MafiaGameEngine(repository, broadcaster, mock(TopicService.class),
            mock(MafiaTimings.class), mock(ThreadPoolTaskScheduler.class), queue);
    private final BackendBridge bridge = mock(BackendBridge.class);
    private final NativeHub hub = new NativeHub(new OutboundBus(), bridge, queue, mock(PresenceService.class),
            mock(SketchService.class), engine, properties);

    @Test void rejectedJoinDoesNotPinTheConnectionToThatRoom() throws Exception {
        long expiresAt = System.currentTimeMillis() + 60_000;
        for (String room : new String[]{ROOM_A, ROOM_B}) {
            when(bridge.authorize(eq(room), any())).thenReturn(CompletableFuture.completedFuture(
                    new BackendBridge.Identity(room, USER, "taken", 1, expiresAt)));
        }
        engine.join(ROOM_A, "someone-else", "99999999-9999-4999-8999-999999999999", "taken", 1);
        EmbeddedChannel channel = new EmbeddedChannel(DefaultChannelId.newInstance());
        SocketSession session = hub.open(channel, "cookie");
        try {
            hub.handle(session, join(ROOM_A));
            await(() -> mockingDetails(broadcaster).getInvocations().stream()
                    .anyMatch(call -> call.getMethod().getName().equals("error")));
            assertNull(repository.roomOfSession(session.id));

            hub.handle(session, join(ROOM_B));
            await(() -> ROOM_B.equals(repository.roomOfSession(session.id)));

            hub.close(session);
            // disconnect unbinds, then removes the emptied lobby, in one room-queue task
            await(() -> repository.roomOfSession(session.id) == null && repository.get(ROOM_B) == null);
        } finally {
            channel.finishAndReleaseAll();
            queue.shutdown();
        }
    }

    @Test void secondRoomIsRefusedWhileJoinedToFirst() throws Exception {
        long expiresAt = System.currentTimeMillis() + 60_000;
        for (String room : new String[]{ROOM_A, ROOM_B}) {
            when(bridge.authorize(eq(room), any())).thenReturn(CompletableFuture.completedFuture(
                    new BackendBridge.Identity(room, USER, "me", 1, expiresAt)));
        }
        EmbeddedChannel channel = new EmbeddedChannel(DefaultChannelId.newInstance());
        SocketSession session = hub.open(channel, "cookie");
        try {
            hub.handle(session, join(ROOM_A));
            await(() -> ROOM_A.equals(repository.roomOfSession(session.id)));
            hub.handle(session, join(ROOM_B));
            hub.handle(session, "{\"op\":\"PING\"}"); // drains after the refused join on the session chain
            await(() -> outboundContains(channel, "PONG"));
            assertEquals(ROOM_A, repository.roomOfSession(session.id));
            assertNull(repository.get(ROOM_B) == null ? null : repository.get(ROOM_B).getPlayers().get("me"));
        } finally {
            hub.close(session);
            channel.finishAndReleaseAll();
            queue.shutdown();
        }
    }

    private static String join(String room) {
        return "{\"op\":\"SEND\",\"destination\":\"/ws/send/sketchmafia/join/" + room + "\",\"body\":{}}";
    }

    private static boolean outboundContains(EmbeddedChannel channel, String text) {
        channel.runPendingTasks();
        Object frame;
        boolean found = false;
        while ((frame = channel.readOutbound()) != null) {
            if (frame instanceof io.netty.handler.codec.http.websocketx.TextWebSocketFrame t) {
                found |= t.text().contains(text);
                t.release();
            }
        }
        return found;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < until) { if (condition.getAsBoolean()) return; Thread.sleep(10); }
        assertTrue(condition.getAsBoolean(), "condition timed out");
    }
}
