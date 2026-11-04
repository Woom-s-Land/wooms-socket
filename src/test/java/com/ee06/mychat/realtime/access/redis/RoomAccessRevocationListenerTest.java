package com.ee06.mychat.realtime.access.redis;

import com.ee06.mychat.realtime.websocket.NativeHub;
import com.ee06.mychat.realtime.backend.BackendBridge;
import com.ee06.mychat.realtime.session.OutboundBus;
import com.ee06.mychat.realtime.presence.PresenceService;
import com.ee06.mychat.realtime.room.RoomQueue;
import com.ee06.mychat.realtime.sketch.SketchService;
import com.ee06.mychat.realtime.config.SocketProperties;
import com.ee06.mychat.realtime.session.SocketSession;
import com.ee06.mychat.realtime.access.event.RoomAccessRevoked;
import com.ee06.mychat.realtime.access.port.RoomAccessRevocationHandler;
import com.ee06.mychat.realtime.mafia.engine.MafiaGameEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoomAccessRevocationListenerTest {
    private static final String ROOM = "22222222-2222-4222-8222-222222222222";
    private static final String MEMBER = "33333333-3333-4333-8333-333333333333";

    @Test void passiveTargetSessionClosesButOtherUserAndRoomStayOpenAndDuplicateIsIgnored() throws Exception {
        Fixture f = new Fixture();
        try {
            EmbeddedChannel targetChannel = f.channel();
            EmbeddedChannel otherUserChannel = f.channel();
            EmbeddedChannel otherRoomChannel = f.channel();
            SocketSession target = f.hub.open(targetChannel, "cookie-not-logged");
            SocketSession otherUser = f.hub.open(otherUserChannel, "cookie-not-logged");
            SocketSession otherRoom = f.hub.open(otherRoomChannel, "cookie-not-logged");
            otherRoom.authenticatedUserUuid = "55555555-5555-4555-8555-555555555555";
            otherRoom.identities.put(UUID.randomUUID().toString(), CompletableFuture.completedFuture(identity(UUID.randomUUID().toString(), otherRoom.authenticatedUserUuid)));
            target.authenticatedUserUuid = MEMBER;
            target.identities.put(ROOM, CompletableFuture.completedFuture(identity(ROOM, MEMBER)));
            otherUser.authenticatedUserUuid = "44444444-4444-4444-8444-444444444444";
            otherUser.identities.put(ROOM, CompletableFuture.completedFuture(identity(ROOM, "44444444-4444-4444-8444-444444444444")));
            f.bus.subscribe(target, "target-sub", "/ws/wooms/chat/" + ROOM);
            f.bus.subscribe(otherUser, "other-sub", "/ws/wooms/chat/" + ROOM);

            byte[] payload = f.mapper.writeValueAsBytes(event());
            RoomAccessRevoked parsed = f.mapper.readValue(payload, RoomAccessRevoked.class);
            assertTrue(parsed.isValid(), "event fixture failed validation: " + new String(payload, java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(targetChannel.isActive());
            assertEquals(3, f.bus.sessionsSnapshot().size());
            DefaultMessage message = new DefaultMessage(new byte[0], payload);
            f.listener.onMessage(message, null);
            f.listener.onMessage(message, null);
            assertTrue(target.revokedRooms.contains(ROOM), "target room was not marked revoked");
            awaitClosed(targetChannel);

            assertTrue(otherUserChannel.isOpen());
            assertTrue(otherRoomChannel.isOpen());
            assertEquals(4403, closeCode(targetChannel));
            f.bus.publish("/ws/wooms/chat/" + ROOM, java.util.Map.of("content", "blocked"));
            assertNull(targetChannel.readOutbound());
            verify(f.presence, timeout(1000)).onDisconnect(target, ROOM);
        } finally { f.close(); }
    }

    @Test void revocationDuringAuthorizationPreventsLateSubscriptionInstallation() throws Exception {
        Fixture f = new Fixture();
        try {
            EmbeddedChannel channel = f.channel();
            SocketSession session = f.hub.open(channel, "cookie-not-logged");
            CompletableFuture<BackendBridge.Identity> response = new CompletableFuture<>();
            when(f.bridge.authorize(eq(ROOM), any())).thenReturn(response);
            f.hub.handle(session, "{\"op\":\"SUBSCRIBE\",\"id\":\"sub\",\"destination\":\"/ws/wooms/move/" + ROOM + "\"}");
            await(() -> session.identities.containsKey(ROOM) && !session.identities.get(ROOM).isDone());

            f.listener.onMessage(new DefaultMessage(new byte[0], f.mapper.writeValueAsBytes(event())), null);
            assertFalse(session.revokedRooms.contains(ROOM), "do not decide until trusted identity is known");
            assertTrue(channel.isOpen());
            response.complete(identity(ROOM, MEMBER));
            awaitClosed(channel);

            assertTrue(session.revokedRooms.contains(ROOM));
            assertTrue(session.subscriptions.isEmpty());
            verify(f.presence, never()).onSubscribe(any(), any(), anyString());
        } finally { f.close(); }
    }

    @Test void malformedAndUnsupportedVersionEventsAreIgnored() throws Exception {
        Fixture f = new Fixture();
        try {
            EmbeddedChannel channel = f.channel();
            SocketSession session = f.hub.open(channel, "cookie-not-logged");
            session.identities.put(ROOM, CompletableFuture.completedFuture(identity(ROOM, MEMBER)));
            f.listener.onMessage(new DefaultMessage(new byte[0], "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)), null);
            RoomAccessRevoked invalid = new RoomAccessRevoked(2, UUID.randomUUID(), RoomAccessRevoked.TYPE,
                    UUID.fromString(ROOM), UUID.fromString(MEMBER), "MEMBER_LEFT", Instant.now());
            f.listener.onMessage(new DefaultMessage(new byte[0], f.mapper.writeValueAsBytes(invalid)), null);
            channel.runPendingTasks();
            assertTrue(channel.isOpen());
            assertFalse(session.revokedRooms.contains(ROOM));
        } finally { f.close(); }
    }

    private static BackendBridge.Identity identity(String room, String user) {
        return new BackendBridge.Identity(room, user, "member", 1, System.currentTimeMillis() + 60_000);
    }

    private static RoomAccessRevoked event() {
        return new RoomAccessRevoked(1, UUID.fromString("11111111-1111-4111-8111-111111111111"),
                RoomAccessRevoked.TYPE, UUID.fromString(ROOM), UUID.fromString(MEMBER), "MEMBER_LEFT", Instant.now());
    }

    private static int closeCode(EmbeddedChannel channel) {
        Object outbound;
        int code = -1;
        while ((outbound = channel.readOutbound()) != null) {
            if (outbound instanceof CloseWebSocketFrame frame) code = frame.statusCode();
            if (outbound instanceof TextWebSocketFrame frame) frame.release();
            else if (outbound instanceof CloseWebSocketFrame frame) frame.release();
        }
        return code;
    }

    private static void awaitClosed(EmbeddedChannel channel) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < until) {
            channel.runScheduledPendingTasks();
            channel.runPendingTasks();
            if (!channel.isOpen()) return;
            Thread.sleep(10);
        }
        assertFalse(channel.isOpen(), "channel remained open");
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < until) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition timed out");
    }

    private static final class Fixture implements AutoCloseable {
        final BackendBridge bridge = mock(BackendBridge.class);
        final PresenceService presence = mock(PresenceService.class);
        final SocketProperties properties = new SocketProperties();
        final RoomQueue queue = new RoomQueue(properties);
        final OutboundBus bus = new OutboundBus();
        final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        final NativeHub hub = new NativeHub(bus, bridge, queue, presence, mock(SketchService.class),
                mock(MafiaGameEngine.class), properties);
        final RoomAccessRevocationListener listener = new RoomAccessRevocationListener(hub, mapper);

        EmbeddedChannel channel() { return new EmbeddedChannel(DefaultChannelId.newInstance()); }

        @Override public void close() {
            for (SocketSession session : bus.sessionsSnapshot()) hub.close(session);
            queue.shutdown();
        }
    }
}
