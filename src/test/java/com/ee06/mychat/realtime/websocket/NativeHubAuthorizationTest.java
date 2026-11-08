package com.ee06.mychat.realtime.websocket;

import com.ee06.mychat.realtime.backend.BackendBridge;
import com.ee06.mychat.realtime.config.SocketProperties;
import com.ee06.mychat.realtime.presence.PresenceService;
import com.ee06.mychat.realtime.room.RoomQueue;
import com.ee06.mychat.realtime.session.OutboundBus;
import com.ee06.mychat.realtime.session.SocketSession;
import com.ee06.mychat.realtime.sketch.SketchService;
import com.ee06.mychat.realtime.access.event.RoomAccessRevoked;
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
    private static final String USER_A = "11111111-1111-4111-8111-111111111111";
    private static final String USER_B = "22222222-2222-4222-8222-222222222222";
    private static final String OTHER_ROOM = "33333333-3333-4333-8333-333333333333";

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
            assertEquals("u", f.session.authenticatedUserUuid);
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

    @Test void revocationDuringInitialAuthorizationClosesOnlyMatchingUserAfterIdentityArrives() throws Exception {
        Fixture f = new Fixture();
        EmbeddedChannel otherChannel = new EmbeddedChannel(DefaultChannelId.newInstance());
        SocketSession otherSession = f.hub.open(otherChannel, "cookie-user-b");
        try {
            CompletableFuture<BackendBridge.Identity> responseA = new CompletableFuture<>();
            CompletableFuture<BackendBridge.Identity> responseB = new CompletableFuture<>();
            when(f.bridge.authorize(eq(ROOM), eq("cookie-is-test-only"))).thenReturn(responseA);
            when(f.bridge.authorize(eq(ROOM), eq("cookie-user-b"))).thenReturn(responseB);
            f.hub.handle(f.session, command("SUBSCRIBE", "id", "a", "destination", "/ws/wooms/move/" + ROOM));
            f.hub.handle(otherSession, command("SUBSCRIBE", "id", "b", "destination", "/ws/wooms/move/" + ROOM));
            verify(f.bridge, timeout(1000).times(2)).authorize(eq(ROOM), any());

            RoomAccessRevoked event = eventFor(USER_B);
            f.hub.onRoomAccessRevoked(event);
            assertTrue(f.channel.isOpen(), "unknown identity must not cause a blanket close");
            assertTrue(otherChannel.isOpen(), "wait for trusted identity before deciding the target session");

            responseA.complete(new BackendBridge.Identity(ROOM, USER_A, "a", 1,
                    System.currentTimeMillis() + 30_000));
            await(() -> f.session.subscriptions.containsKey("a"));
            assertTrue(f.channel.isOpen(), "a different user in the same room must remain connected");

            responseB.complete(new BackendBridge.Identity(ROOM, USER_B, "b", 1,
                    System.currentTimeMillis() + 30_000));
            awaitClosed(otherChannel);
            assertTrue(otherSession.subscriptions.isEmpty());
            assertFalse(f.session.closed);
        } finally {
            f.hub.close(otherSession);
            otherChannel.finishAndReleaseAll();
            f.close();
        }
    }

    @Test void revokeAtIdentityCompletionBoundaryIsComparedBeforeFuturePublishesIdentity() throws Exception {
        Fixture f = new Fixture();
        try {
            CompletableFuture<BackendBridge.Identity> response = new CompletableFuture<>();
            when(f.bridge.authorize(eq(ROOM), any())).thenReturn(response);
            f.hub.handle(f.session, command("SUBSCRIBE", "id", "sub", "destination", "/ws/wooms/move/" + ROOM));
            verify(f.bridge, timeout(1000)).authorize(eq(ROOM), any());

            java.util.concurrent.CountDownLatch completing = new java.util.concurrent.CountDownLatch(1);
            Thread completionThread;
            synchronized (f.session) {
                completionThread = new Thread(() -> {
                    completing.countDown();
                    response.complete(new BackendBridge.Identity(ROOM, USER_A, "a", 1,
                            System.currentTimeMillis() + 30_000));
                });
                completionThread.start();
                assertTrue(completing.await(1, TimeUnit.SECONDS));
                f.hub.onRoomAccessRevoked(eventFor(USER_B));
            }
            completionThread.join(1_000);
            assertFalse(completionThread.isAlive());
            await(() -> f.session.subscriptions.containsKey("sub"));
            assertTrue(f.channel.isOpen());
            assertFalse(f.session.revokedRooms.contains(ROOM));
        } finally { f.close(); }
    }

    @Test void revocationForAnotherRoomKeepsTrustedUserSessionOpen() throws Exception {
        Fixture f = new Fixture();
        try {
            when(f.bridge.authorize(eq(ROOM), any())).thenReturn(CompletableFuture.completedFuture(
                    new BackendBridge.Identity(ROOM, USER_A, "a", 1, System.currentTimeMillis() + 30_000)));
            f.hub.handle(f.session, command("SUBSCRIBE", "id", "a", "destination", "/ws/wooms/chat/" + ROOM));
            await(() -> f.session.subscriptions.containsKey("a"));

            f.hub.onRoomAccessRevoked(eventFor(USER_A, OTHER_ROOM));

            assertTrue(f.channel.isOpen(), "same principal in a different room must remain connected");
            assertFalse(f.session.closed);
            assertTrue(f.session.revokedRooms.isEmpty());
        } finally { f.close(); }
    }

    @Test void revocationForAnotherRoomDoesNotMarkUnknownRoomAuthorization() throws Exception {
        Fixture f = new Fixture();
        try {
            CompletableFuture<BackendBridge.Identity> response = new CompletableFuture<>();
            when(f.bridge.authorize(eq(OTHER_ROOM), any())).thenReturn(response);
            f.hub.handle(f.session, command("SUBSCRIBE", "id", "other", "destination", "/ws/wooms/move/" + OTHER_ROOM));
            verify(f.bridge, timeout(1000)).authorize(eq(OTHER_ROOM), any());

            f.hub.onRoomAccessRevoked(eventFor(USER_A, ROOM));
            response.complete(new BackendBridge.Identity(OTHER_ROOM, USER_A, "a", 1,
                    System.currentTimeMillis() + 30_000));

            await(() -> f.session.subscriptions.containsKey("other"));
            assertTrue(f.channel.isOpen(), "unrelated pending room authorization must remain connected");
            assertFalse(f.session.revokedRooms.contains(OTHER_ROOM));
        } finally { f.close(); }
    }

    @Test void reauthorizationForAnotherRoomCannotChangeTrustedSessionUser() throws Exception {
        Fixture f = new Fixture();
        try {
            when(f.bridge.authorize(eq(ROOM), any())).thenReturn(CompletableFuture.completedFuture(
                    new BackendBridge.Identity(ROOM, USER_A, "a", 1, System.currentTimeMillis() + 30_000)));
            when(f.bridge.authorize(eq(OTHER_ROOM), any())).thenReturn(CompletableFuture.completedFuture(
                    new BackendBridge.Identity(OTHER_ROOM, USER_B, "b", 1, System.currentTimeMillis() + 30_000)));
            f.hub.handle(f.session, command("SUBSCRIBE", "id", "first", "destination", "/ws/wooms/chat/" + ROOM));
            await(() -> f.session.subscriptions.containsKey("first"));
            assertEquals(USER_A, f.session.authenticatedUserUuid);

            f.hub.handle(f.session, "{\"op\":\"SEND\",\"destination\":\"/ws/send/chat/" + OTHER_ROOM
                    + "\",\"body\":{\"content\":\"must-not-run\"}}");
            awaitClosed(f.channel);

            verify(f.presence, never()).send(any(), any(), anyString(), any());
            assertTrue(f.session.revokedRooms.isEmpty());
            assertEquals(4403, closeCode(f.channel));
        } finally { f.close(); }
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

    @Test void unsupportedGameDestinationsAreRejectedBeforeAuthorization() throws Exception {
        Fixture f = new Fixture();
        try {
            f.hub.handle(f.session, command("SUBSCRIBE", "id", "game-sub",
                    "destination", "/ws/wooms/sketchmafia/state/" + ROOM));
            f.hub.handle(f.session, command("SEND",
                    "destination", "/ws/send/sketchmafia/join/" + ROOM, "body", "{}"));

            f.channel.runPendingTasks();
            int rejected = 0;
            Object outbound;
            while ((outbound = f.channel.readOutbound()) != null) {
                if (outbound instanceof TextWebSocketFrame frame) {
                    if (frame.text().contains("destination not allowed")) rejected++;
                    frame.release();
                } else if (outbound instanceof CloseWebSocketFrame frame) {
                    frame.release();
                }
            }
            assertEquals(2, rejected);
            verifyNoInteractions(f.bridge);
            assertTrue(f.session.subscriptions.isEmpty());
        } finally {
            f.close();
        }
    }

    private static String command(String op, String... kv) throws Exception {
        ObjectMapper mapper = new ObjectMapper(); var node = mapper.createObjectNode().put("op", op);
        for (int i=0;i<kv.length;i+=2) node.put(kv[i], kv[i+1]);
        return mapper.writeValueAsString(node);
    }
    private static RoomAccessRevoked eventFor(String userUuid) {
        return new RoomAccessRevoked(1, UUID.randomUUID(), RoomAccessRevoked.TYPE,
                UUID.fromString(ROOM), UUID.fromString(userUuid), "MEMBER_LEFT", java.time.Instant.now());
    }
    private static RoomAccessRevoked eventFor(String userUuid, String roomId) {
        return new RoomAccessRevoked(1, UUID.randomUUID(), RoomAccessRevoked.TYPE,
                UUID.fromString(roomId), UUID.fromString(userUuid), "MEMBER_LEFT", java.time.Instant.now());
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
        final NativeHub hub=new NativeHub(bus,bridge,queue,presence,mock(SketchService.class),properties);
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
