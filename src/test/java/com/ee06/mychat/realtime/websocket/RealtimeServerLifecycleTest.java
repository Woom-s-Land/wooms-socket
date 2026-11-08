package com.ee06.mychat.realtime.websocket;

import com.ee06.mychat.realtime.config.SocketProperties;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RealtimeServerLifecycleTest {

    @Test void bindFailureReleasesEventLoopThreads() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            long before = realtimeThreads();
            RealtimeServer server = new RealtimeServer(properties(occupied.getLocalPort(), 65536), mock(NativeHub.class));
            assertThrows(Exception.class, server::start);
            assertFalse(server.isRunning());
            awaitTrue(() -> realtimeThreads() <= before, "event-loop threads leaked after failed start");
            server.stop(); // still safe after a failed start
        }
    }

    @Test void stopClosesLiveConnectionsAndRunsSessionCleanupBeforeReturning() throws Exception {
        NativeHub hub = mock(NativeHub.class);
        int port = freePort();
        RealtimeServer server = new RealtimeServer(properties(port, 1024), hub);
        server.start();
        try {
            Client client = Client.connect(port);
            assertEquals("{\"op\":\"CONNECTED\"}", client.messages.poll(3, TimeUnit.SECONDS));
            verify(hub, timeout(3000)).open(any(), any());

            client.socket.sendText("{\"op\":\"PING\"}", true).get(3, TimeUnit.SECONDS);
            verify(hub, timeout(3000)).handle(any(), eq("{\"op\":\"PING\"}"));

            server.stop();
            verify(hub, atLeastOnce()).close(any()); // channelInactive ran before stop() returned
            assertFalse(server.isRunning());
            client.closed.get(3, TimeUnit.SECONDS);
            try (ServerSocket rebound = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                assertTrue(rebound.isBound(), "listening socket was released");
            }
        } finally { server.stop(); }
    }

    @Test void frameAboveConfiguredLimitClosesConnectionWithoutReachingHub() throws Exception {
        NativeHub hub = mock(NativeHub.class);
        int port = freePort();
        RealtimeServer server = new RealtimeServer(properties(port, 1024), hub);
        server.start();
        try {
            Client client = Client.connect(port);
            client.messages.poll(3, TimeUnit.SECONDS);
            client.socket.sendText("x".repeat(1025), true);
            client.closed.get(3, TimeUnit.SECONDS);
            verify(hub, timeout(3000).atLeastOnce()).close(any()); // exceptionCaught and channelInactive both close; close is idempotent
            verify(hub, never()).handle(any(), anyString());
        } finally { server.stop(); }
    }

    private static SocketProperties properties(int port, int maxFrameBytes) {
        SocketProperties properties = new SocketProperties();
        properties.setPort(port);
        properties.setMaxFrameBytes(maxFrameBytes);
        properties.setStorageSecret("test-only-storage-secret-value-32chars");
        return properties;
    }

    private static long realtimeThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && thread.getName().startsWith("realtime-")).count();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); }
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String message) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) { if (condition.getAsBoolean()) return; Thread.sleep(20); }
        assertTrue(condition.getAsBoolean(), message);
    }

    private static final class Client implements WebSocket.Listener {
        final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        WebSocket socket;

        static Client connect(int port) throws Exception {
            Client client = new Client();
            client.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), client).get(3, TimeUnit.SECONDS);
            return client;
        }

        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messages.add(data.toString());
            webSocket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(null);
            return null;
        }
        @Override public void onError(WebSocket webSocket, Throwable error) { closed.complete(null); }
    }
}
