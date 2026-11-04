package com.ee06.mychat.realtime.backend;

import com.ee06.mychat.realtime.config.SocketProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BackendBridgeStorageTest {
    private static final String SECRET = "test-only-service-secret-32-bytes-long";

    @Test
    void putWallUsesServiceKeyAndNeverForwardsExpiredUserCookie() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> serviceKey = new AtomicReference<>();
        AtomicReference<String> cookie = new AtomicReference<>();
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            serviceKey.set(exchange.getRequestHeaders().getFirst("X-Wooms-Realtime-Key"));
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            SocketProperties properties = new SocketProperties();
            properties.setBackendUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setStorageSecret(SECRET);
            BackendBridge bridge = new BackendBridge(properties);

            assertTrue(bridge.putWall("00000000-0000-0000-0000-000000000001",
                    "Authorization=expired-user-cookie", blankGrid()).join().isObject());
            assertEquals("/api/auth/realtime/rooms/00000000-0000-0000-0000-000000000001/wall", path.get());
            assertEquals(SECRET, serviceKey.get());
            assertNull(cookie.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void putWallWithoutServiceSecretFailsBeforeSendingHttpRequest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicBoolean received = new AtomicBoolean();
        server.createContext("/", exchange -> {
            received.set(true);
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            SocketProperties properties = new SocketProperties();
            properties.setBackendUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setStorageSecret("");
            BackendBridge bridge = new BackendBridge(properties);

            CompletionException failure = assertThrows(CompletionException.class,
                    () -> bridge.putWall("00000000-0000-0000-0000-000000000001",
                            "Authorization=expired-user-cookie", blankGrid()).join());
            assertEquals("Internal wall storage key is not configured", failure.getCause().getMessage());
            assertFalse(received.get());
        } finally {
            server.stop(0);
        }
    }

    private static int[][] blankGrid() {
        int[][] pixels = new int[32][32];
        for (int[] row : pixels) java.util.Arrays.fill(row, -1);
        return pixels;
    }
}
