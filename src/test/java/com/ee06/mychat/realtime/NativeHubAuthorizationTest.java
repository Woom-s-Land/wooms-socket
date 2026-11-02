package com.ee06.mychat.realtime;

import com.ee06.mychat.realtime.mafia.engine.MafiaGameEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.DefaultChannelId;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeHubAuthorizationTest {
    private static final String ROOM = UUID.randomUUID().toString();

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
            recheck.completeExceptionally(new BackendBridge.BackendStatusException(403));
            awaitClosed(f.channel);
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
        final RoomQueue queue=new RoomQueue(new SocketProperties());
        final EmbeddedChannel channel=new EmbeddedChannel(DefaultChannelId.newInstance());
        final OutboundBus bus=new OutboundBus();
        final NativeHub hub=new NativeHub(bus,bridge,queue,presence,mock(SketchService.class),mock(MafiaGameEngine.class));
        final SocketSession session=hub.open(channel,"cookie-is-test-only");
        @Override public void close() { hub.close(session); channel.finishAndReleaseAll(); queue.shutdown(); }
    }
}
