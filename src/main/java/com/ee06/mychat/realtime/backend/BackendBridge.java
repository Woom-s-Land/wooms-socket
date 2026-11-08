package com.ee06.mychat.realtime.backend;

import com.ee06.mychat.realtime.config.SocketProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Component
public class BackendBridge {
    private final SocketProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public BackendBridge(SocketProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getBackendTimeout())
                .build();
    }

    public record Identity(String roomId, String userUuid, String nickname, Integer costume, long expiresAt) {}

    public CompletableFuture<Identity> authorize(String roomId, String cookie) {
        return request("GET", roomPath(roomId) + "/session", cookie, null)
                .thenApply(json -> objectMapper.convertValue(json, Identity.class));
    }

    public CompletableFuture<JsonNode> wall(String roomId, String cookie) {
        return request("GET", roomPath(roomId) + "/wall", cookie, null);
    }

    /** Saves the shared wall with the service key; user cookies are never forwarded here. */
    public CompletableFuture<JsonNode> putWall(String roomId, int[][] pixels) {
        try {
            String secret = properties.getStorageSecret();
            if (secret == null || secret.length() < 32) return CompletableFuture.failedFuture(new IllegalStateException("Internal wall storage key is not configured"));
            String path = "/api/auth/realtime/rooms/" + UUID.fromString(roomId) + "/wall";
            URI uri = URI.create(properties.getBackendUrl()).resolve(path);
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(properties.getBackendTimeout())
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header("X-Wooms-Realtime-Key", secret)
                    .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(pixelsBody(pixels))))
                    .build();
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(this::parseResponse);
        } catch (Exception e) { return CompletableFuture.failedFuture(e); }
    }

    public CompletableFuture<JsonNode> archive(String roomId, String cookie, int[][] pixels) {
        return request("POST", roomPath(roomId) + "/wall/archive", cookie, pixelsBody(pixels));
    }

    private CompletableFuture<JsonNode> request(String method, String path, String cookie, Object body) {
        try {
            URI uri = URI.create(properties.getBackendUrl()).resolve(path);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(properties.getBackendTimeout())
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            if (cookie != null && !cookie.isBlank()) builder.header(HttpHeaders.COOKIE, cookie);
            String json = body == null ? "" : objectMapper.writeValueAsString(body);
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json));
            return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .thenApply(this::parseResponse);
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private JsonNode parseResponse(HttpResponse<String> response) {
        if (response.statusCode() != 200) throw new CompletionException(new BackendStatusException(response.statusCode()));
        try { return objectMapper.readTree(response.body()); }
        catch (Exception e) { throw new CompletionException(new IllegalStateException("Invalid backend JSON response", e)); }
    }

    private String roomPath(String roomId) {
        UUID uuid = UUID.fromString(roomId);
        return "/api/realtime/rooms/" + uuid;
    }

    private static Object pixelsBody(int[][] pixels) {
        return new PixelsBody(pixels);
    }

    private record PixelsBody(int[][] pixels) {}

    public static final class BackendStatusException extends RuntimeException {
        private final int statusCode;
        public BackendStatusException(int statusCode) {
            super("Backend returned HTTP status " + statusCode);
            this.statusCode = statusCode;
        }
        public int getStatusCode() { return statusCode; }
    }
}
