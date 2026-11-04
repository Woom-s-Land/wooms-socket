package com.ee06.mychat.realtime.websocket;

import com.ee06.mychat.realtime.backend.BackendBridge;
import com.ee06.mychat.realtime.config.SocketProperties;
import com.ee06.mychat.realtime.presence.PresenceService;
import com.ee06.mychat.realtime.room.RoomQueue;
import com.ee06.mychat.realtime.session.OutboundBus;
import com.ee06.mychat.realtime.session.SocketSession;
import com.ee06.mychat.realtime.sketch.SketchService;
import com.ee06.mychat.realtime.mafia.engine.MafiaGameEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.DefaultChannelId;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeHubAuthorizationTest {
    private static final String ROOM = UUID.randomUUID().toString();

    @Test void defaultsUseSixtySecondCacheAndThirtyToSixtySecondDistributedRevalidation() {
        SocketProperties properties = new SocketProperties();
        assertEquals(java.time.Duration.ofSeconds(60), properties.getAuthorizationCacheTtl());
        assertEquals(java.time.Duration.ofSeconds(30), properties.getAuthorizationRevalidationMin());
        assertEquals(java.time.Duration.ofSeconds(60), properties.getAuthorizationRevalidationMax());
    }

    @Test void successfulAuthorizationPreservesBackendExpiryAndSchedulesWithinConfiguredJitter() throws Exception {
        Fixture f = new Fixture();
        try {
            long expiresAt = System.currentTimeMillis() + 60_000;
            when(f.bridge.authorize(eq(ROOM), any())).thenReturn(CompletableFuture.completedFuture(
                    new BackendBridge.Identity(ROOM, "u", "nick", 1, expiresAt)));
            f.hub.handle(f.session, command("SUBSCRIBE", "id", "m", "destination", "/ws/wooms/move/" + ROOM));
            await(() -> f.session.subscriptions.containsKey("m"));
            assertEquals(expiresAt, f.session.expiryAt.get(ROOM));
            assertNotNull(f.session.authorizationTask);
            long delayMs = f.session.authorizationTask.getDelay(TimeUnit.MILLISECONDS);
            assertTrue(delayMs > 250 && delayMs <= 800, "revalidation delay outside configured spread: " + delayMs);
        } finally { f.close(); }
    }

    @Test void delayedExpiredOrMismatchedIdentityNeverSubscribesAndClosesChannel() throws Exception {
        for (boolean mismatch : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            try {
                CompletableFuture<BackendBridge.Identity> response = new CompletableFuture<>();
                when(f.bridge.authorize(eq(ROOM), any())).thenReturn(response);
                f.hub.handle(f.session, command("SUBSCRIBE", "id", "m", "destination", "/ws/wooms/move/" + ROOM));
                assertTrue(f.channel.isOpen()); // wait for the controlled backend response
                String identityRoom = mismatch ? UUID.randomUUID().toString() : ROOM;
                long expiry = mismatch ? System.currentTimeMillis() + 60_000 : System.currentTimeMillis() - 1;
                response.complete(new BackendBridge.Identity(identityRoom, "u", "nick", 1, expiry));
                awaitClosed(f.channel);
                verify(f.presence, never()).onSubscribe(any(), any(), anyString());
                assertTrue(f.session.subscriptions.isEmpty());
            } finally { f.close(); }
        }
    }

    @Test void failedSecondAuthorizationBlocksSendAndClosesChannel() throws Exception {
        Fixture f = new Fixture();
        try {
            CompletableFuture<BackendBridge.Identity> initial = CompletableFuture.completedFuture(
                    new BackendBridge.Identity(ROOM, "u", "nick", 1, System.currentTimeMillis() + 30_000));
            CompletableFuture<BackendBridge.Identity> recheck = new CompletableFuture<>();
            when(f.bridge.authorize(eq(ROOM), any())).thenReturn(initial, recheck);
            f.hub.handle(f.session, command("SUBSCRIBE", "id", "m", "destination", "/ws/wooms/move/" + ROOM));
            await(() -> f.session.subscriptions.containsKey("m"));
            f.session.lastValidatedAt.put(ROOM, 0L);
            f.hub.handle(f.session, "{\"op\":\"SEND\",\"destination\":\"/ws/send/chat/" + ROOM + "\",\"body\":{\"content\":\"x\"}}");
            verify(f.bridge, timeout(1000).times(2)).authorize(eq(ROOM), any());
            recheck.completeExceptionally(new BackendBridge.BackendStatusException(403));
            assertTrue(f.session.closed, "authorization failure must block the session before event-loop close runs");
            awaitClosed(f.channel);
            verify(f.presence, never()).send(any(), any(), anyString(), any());
            assertEquals(4403, closeCode(f.channel));
        } finally { f.close(); }
    }

    @Test void temporaryBackendFailureUsesServiceUnavailableCloseCode() throws Exception {
        Fixture f = new Fixture();
        try {
            CompletableFuture<BackendBridge.Identity> initial = CompletableFuture.completedFuture(
                    new BackendBridge.Identity(ROOM, "u", "nick", 1, System.currentTimeMillis() + 30_000));
            CompletableFuture<BackendBridge.Identity> recheck = new CompletableFuture<>();
            when(f.bridge.authorize(eq(ROOM), any())).thenReturn(initial, recheck);
            f.hub.handle(f.session, command("SUBSCRIBE", "id", "m", "destination", "/ws/wooms/move/" + ROOM));
            await(() -> f.session.subscriptions.containsKey("m"));
            f.session.lastValidatedAt.put(ROOM, 0L);
            f.hub.handle(f.session, "{\"op\":\"SEND\",\"destination\":\"/ws/send/chat/" + ROOM + "\",\"body\":{\"content\":\"x\"}}");
            recheck.completeExceptionally(new BackendBridge.BackendStatusException(503));
            awaitClosed(f.channel);
            assertEquals(1013, closeCode(f.channel));
            verify(f.presence, never()).send(any(), any(), anyString(), any());
        } finally { f.close(); }
    }

    @Test void tokenExpiryClosesChannelAutomatically() throws Exception {
        Fixture f = new Fixture();
        try {
            when(f.bridge.authorize(eq(ROOM), any())).thenReturn(CompletableFuture.completedFuture(
                    new BackendBridge.Identity(ROOM, "u", "nick", 1, System.currentTimeMillis() + 150)));
            f.hub.handle(f.session, command("SUBSCRIBE", "id", "m", "destination", "/ws/wooms/move/" + ROOM));
            awaitClosed(f.channel);
            assertTrue(f.session.closed);
        } finally { f.close(); }
    }

    @Test void passiveSubscriptionRevalidatesAuthorizationAndClosesAfterRevocation() throws Exception {
        Fixture f = new Fixture();
        try {
            CompletableFuture<BackendBridge.Identity> initial = CompletableFuture.completedFuture(
                    new BackendBridge.Identity(ROOM, "u", "nick", 1, System.currentTimeMillis() + 30_000));
            CompletableFuture<BackendBridge.Identity> revoked = CompletableFuture.failedFuture(
                    new BackendBridge.BackendStatusException(403));
            when(f.bridge.authorize(eq(ROOM), any())).thenReturn(initial, revoked);
            f.hub.handle(f.session, command("SUBSCRIBE", "id", "passive", "destination", "/ws/wooms/move/" + ROOM));
            await(() -> f.session.subscriptions.containsKey("passive"));

            awaitClosed(f.channel, 8);
            verify(f.bridge, atLeast(2)).authorize(eq(ROOM), any());
            assertTrue(f.session.closed);
        } finally { f.close(); }
    }

    @Test void successfulAuthorizationCompletingAfterCloseDoesNotScheduleSessionTimers() throws Exception {
        Fixture f = new Fixture();
        try {
            CompletableFuture<BackendBridge.Identity> response = new CompletableFuture<>();
            when(f.bridge.authorize(eq(ROOM), any())).thenReturn(response);
            f.hub.handle(f.session, command("SUBSCRIBE", "id", "late", "destination", "/ws/wooms/move/" + ROOM));
            f.hub.close(f.session);
            f.channel.close();

            response.complete(new BackendBridge.Identity(ROOM, "u", "nick", 1,
                    System.currentTimeMillis() + 30_000));

            await(() -> f.session.tail.isDone());
            verify(f.presence, never()).onSubscribe(any(), any(), anyString());
            assertTrue(f.session.expiryTasks.isEmpty());
            assertTrue(f.session.authorizationTask == null || f.session.authorizationTask.isCancelled());
        } finally { f.close(); }
    }

    private static String command(String op, String... kv) throws Exception {
        ObjectMapper mapper = new ObjectMapper(); var node = mapper.createObjectNode().put("op", op);
        for (int i=0;i<kv.length;i+=2) node.put(kv[i], kv[i+1]);
        return mapper.writeValueAsString(node);
    }
    private static void awaitClosed(EmbeddedChannel channel) throws Exception {
        awaitClosed(channel, 3);
    }
    private static void awaitClosed(EmbeddedChannel channel, int timeoutSeconds) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while(System.nanoTime()<until){channel.runScheduledPendingTasks();channel.runPendingTasks();if(!channel.isOpen())return;Thread.sleep(10);}
        assertFalse(channel.isOpen(), "channel did not close before timeout");
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<until){if(condition.getAsBoolean())return;Thread.sleep(10);}
        assertTrue(condition.getAsBoolean(), "condition timed out");
    }

    private static final class Fixture implements AutoCloseable {
        final BackendBridge bridge=mock(BackendBridge.class);
        final PresenceService presence=mock(PresenceService.class);
        final SocketProperties properties=new SocketProperties();
        { properties.setAuthorizationRevalidationMin(java.time.Duration.ofMillis(500));
          properties.setAuthorizationRevalidationMax(java.time.Duration.ofMillis(800)); }
        final RoomQueue queue=new RoomQueue(properties);
        final EmbeddedChannel channel=new EmbeddedChannel(DefaultChannelId.newInstance());
        final OutboundBus bus=new OutboundBus();
        final NativeHub hub=new NativeHub(bus,bridge,queue,presence,mock(SketchService.class),mock(MafiaGameEngine.class),properties);
        final SocketSession session=hub.open(channel,"cookie-is-test-only");
        @Override public void close() { hub.close(session); channel.finishAndReleaseAll(); queue.shutdown(); }
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
}
