package com.ee06.mychat.realtime.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ee06.mychat.realtime.backend.BackendBridge;
import com.ee06.mychat.realtime.config.SocketProperties;
import com.ee06.mychat.realtime.session.OutboundBus;
import com.ee06.mychat.realtime.session.SocketSession;
import com.ee06.mychat.realtime.room.RoomQueue;
import com.ee06.mychat.realtime.presence.PresenceService;
import com.ee06.mychat.realtime.sketch.SketchService;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.Duration;
import com.ee06.mychat.realtime.mafia.engine.MafiaGameEngine;
import com.ee06.mychat.realtime.access.event.RoomAccessRevoked;
import com.ee06.mychat.realtime.access.port.RoomAccessRevocationHandler;

@Slf4j
@Component
public class NativeHub implements RoomAccessRevocationHandler {
    private static final int MAX_PENDING_COMMANDS = 256;
    private static final int MAX_SUBSCRIPTIONS = 32;
    private static final int MAX_FRAME_BYTES = 65_536;
    private static final Pattern PUBLIC_DESTINATION = Pattern.compile(
            "^/ws/wooms/(chat|move|disconnect|sketch/draw|sketchmafia/(?:state|reveal|result|chat))/([0-9a-fA-F-]{36})$");
    private static final Pattern SEND_DESTINATION = Pattern.compile(
            "^/ws/send/(chat|move|sketch/draw|sketch/save|sketchmafia/(?:join|start|submit|vote|chat|guess))/([0-9a-fA-F-]{36})$");
    private static final List<String> PRIVATE_DESTINATIONS = List.of(
            "/user/queue/init", "/user/queue/sketch/init",
            "/user/queue/sketchmafia/role", "/user/queue/sketchmafia/error");

    private final OutboundBus bus;
    private final BackendBridge bridge;
    private final RoomQueue queue;
    private final PresenceService presence;
    private final SketchService sketch;
    private final MafiaGameEngine mafia;
    private final SocketProperties properties;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Map<String, AtomicInteger> pending = new ConcurrentHashMap<>();
    private final Map<String, String> mafiaRooms = new ConcurrentHashMap<>();

    public NativeHub(OutboundBus bus, BackendBridge bridge, RoomQueue queue, PresenceService presence,
                     SketchService sketch, MafiaGameEngine mafia, SocketProperties properties) {
        this.bus = bus;
        this.bridge = bridge;
        this.queue = queue;
        this.presence = presence;
        this.sketch = sketch;
        this.mafia = mafia;
        this.properties = properties;
        validateAuthorizationTiming(properties);
    }

    public SocketSession open(Channel channel, String cookie) {
        SocketSession session = bus.open(channel, cookie);
        pending.put(session.id, new AtomicInteger());
        return session;
    }

    public void handle(SocketSession session, String text) {
        if (session == null || session.closed || !session.channel.isActive()) return;
        AtomicInteger count = pending.computeIfAbsent(session.id, ignored -> new AtomicInteger());
        if (count.incrementAndGet() > MAX_PENDING_COMMANDS) {
            count.decrementAndGet();
            session.channel.close();
            return;
        }
        CompletableFuture<Void> previous = session.tail;
        CompletableFuture<Void> next = previous.handle((ignored, failure) -> null)
                .thenCompose(ignored -> {
                    if (session.closed || !session.channel.isActive()) return CompletableFuture.completedFuture(null);
                    return dispatch(session, text);
                }).toCompletableFuture();
        session.tail = next.whenComplete((ignored, failure) -> {
            count.decrementAndGet();
            if (failure != null) bus.error(session.channel, "command failed");
        }).handle((ignored, failure) -> null);
    }

    private CompletionStage<Void> dispatch(SocketSession session, String text) {
        try {
            if (text == null || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) {
                return failed(session, "frame too large");
            }
            JsonNode command = mapper.readTree(text);
            if (command == null || !command.isObject() || !command.path("op").isTextual()) {
                return failed(session, "invalid command");
            }
            String op = command.path("op").asText();
            return switch (op) {
                case "PING" -> writePong(session);
                case "SUBSCRIBE" -> subscribe(session, command);
                case "UNSUBSCRIBE" -> unsubscribe(session, command);
                case "SEND" -> send(session, command);
                default -> failed(session, "unsupported operation");
            };
        } catch (Exception exception) {
            return failed(session, "invalid command");
        }
    }

    private CompletionStage<Void> subscribe(SocketSession session, JsonNode command) {
        String id = command.path("id").asText(null);
        String destination = command.path("destination").asText(null);
        if (!validSubscriptionId(id)) return failed(session, "invalid subscription id");
        if (session.subscriptions.size() >= MAX_SUBSCRIPTIONS && !session.subscriptions.containsKey(id)) {
            return failed(session, "subscription limit exceeded");
        }
        if (PRIVATE_DESTINATIONS.contains(destination)) {
            bus.subscribe(session, id, destination);
            return CompletableFuture.completedFuture(null);
        }
        Matcher matcher = destination == null ? null : PUBLIC_DESTINATION.matcher(destination);
        if (matcher == null || !matcher.matches()) return failed(session, "destination not allowed");
        String kind = matcher.group(1);
        String roomId = normalizeUuid(matcher.group(2));
        if (roomId == null) return failed(session, "invalid room id");
        return authorize(session, roomId).thenCompose(identity -> queue.run(roomId, () -> {
            synchronized (session) {
                if (session.closed || !session.channel.isActive()) return CompletableFuture.completedFuture(null);
                bus.subscribe(session, id, destination.replace(matcher.group(2), roomId));
                if ("move".equals(kind)) presence.onSubscribe(session, identity, "/ws/wooms/move/" + roomId);
                if ("sketch/draw".equals(kind)) return sketch.onSubscribe(session, identity);
                return CompletableFuture.completedFuture(null);
            }
        }));
    }

    private CompletionStage<Void> unsubscribe(SocketSession session, JsonNode command) {
        String id = command.path("id").asText(null);
        if (!validSubscriptionId(id)) return failed(session, "invalid subscription id");
        bus.unsubscribe(session, id);
        return CompletableFuture.completedFuture(null);
    }

    private CompletionStage<Void> send(SocketSession session, JsonNode command) {
        String destination = command.path("destination").asText(null);
        Matcher matcher = destination == null ? null : SEND_DESTINATION.matcher(destination);
        if (matcher == null || !matcher.matches()) return failed(session, "destination not allowed");
        String kind = matcher.group(1);
        String roomId = normalizeUuid(matcher.group(2));
        if (roomId == null) return failed(session, "invalid room id");
        JsonNode body = command.get("body");
        if (body == null) return failed(session, "missing message body");
        try {
            if (body.isTextual()) body = mapper.readTree(body.asText());
        } catch (Exception exception) {
            return failed(session, "invalid message body");
        }
        if (!body.isObject()) return failed(session, "message body must be an object");
        JsonNode payload = body;
        return authorize(session, roomId).thenCompose(identity -> queue.run(roomId, () -> {
            if ("sketch/draw".equals(kind) || "sketch/save".equals(kind))
                return sketch.send(session, identity, kind, payload);
            synchronized (session) {
                if (session.closed || !session.channel.isActive()) return CompletableFuture.completedFuture(null);
                if (kind.startsWith("sketchmafia/")) return mafiaCommand(session, identity, roomId, kind, payload);
                if ("chat".equals(kind) || "move".equals(kind)) {
                    presence.send(session, identity, kind, payload);
                    return CompletableFuture.completedFuture(null);
                }
            }
            return CompletableFuture.failedFuture(new IllegalArgumentException("unsupported event"));
        }));
    }

    private CompletionStage<Void> mafiaCommand(SocketSession s, BackendBridge.Identity i, String room,
                                               String kind, JsonNode body) {
        switch (kind) {
            case "sketchmafia/join" -> {
                String existing = mafiaRooms.putIfAbsent(s.id, room);
                if (existing != null && !existing.equals(room)) return CompletableFuture.failedFuture(new IllegalStateException("session already joined another mafia room"));
                mafia.join(room, s.id, i.userUuid(), i.nickname(), i.costume());
            }
            case "sketchmafia/start" -> mafia.start(room, s.id, body.has("drawSeconds") && !body.get("drawSeconds").isNull() ? body.get("drawSeconds").asInt() : null);
            case "sketchmafia/submit" -> mafia.submit(room, s.id, mapper.convertValue(body.get("pixels"), int[][].class));
            case "sketchmafia/vote" -> mafia.vote(room, s.id, body.path("target").asText());
            case "sketchmafia/chat" -> mafia.chat(room, s.id, body.path("message").asText());
            case "sketchmafia/guess" -> mafia.guess(room, s.id, body.path("word").asText());
            default -> { return CompletableFuture.failedFuture(new IllegalArgumentException("unsupported mafia operation")); }
        }
        return CompletableFuture.completedFuture(null);
    }

    private CompletionStage<BackendBridge.Identity> authorize(SocketSession session, String roomId) {
        synchronized (session) {
            if (session.closed || !session.channel.isActive())
                return CompletableFuture.failedFuture(new IllegalStateException("session closed"));
            if (session.revokedRooms.contains(roomId)) {
                terminateAuthorization(session, roomId, AuthorizationFailure.REVOKED);
                return CompletableFuture.failedFuture(new IllegalStateException("room access revoked"));
            }

            long now = System.currentTimeMillis();
            CompletableFuture<BackendBridge.Identity> current = session.identities.get(roomId);
            Long validatedAt = session.lastValidatedAt.get(roomId);
            if (current != null && !current.isDone()) return current;
            if (current != null && current.isCompletedExceptionally()) {
                session.identities.remove(roomId, current);
                current = null;
            }
            long cacheTtlMs = properties.getAuthorizationCacheTtl().toMillis();
            if (current != null && validatedAt != null && now - validatedAt < cacheTtlMs) {
                BackendBridge.Identity cached = current.getNow(null);
                if (cached != null && cached.expiresAt() > now) return current;
                if (cached != null) {
                    terminateAuthorization(session, roomId, AuthorizationFailure.DENIED);
                    return CompletableFuture.failedFuture(new IllegalStateException("access token expired"));
                }
            }

            // Publish the in-flight future before starting HTTP. A revocation arriving now can mark and close it.
            CompletableFuture<BackendBridge.Identity> validation = new CompletableFuture<>();
            session.identities.put(roomId, validation);
            try {
                bridge.authorize(roomId, session.cookie).whenComplete((identity, failure) -> {
                    if (failure != null) {
                        terminateAuthorization(session, roomId, unwrap(failure));
                        validation.completeExceptionally(unwrap(failure));
                        return;
                    }
                    try {
                        synchronized (session) {
                            if (session.closed || !session.channel.isActive())
                                throw new IllegalStateException("session closed");
                            if (session.revokedRooms.contains(roomId))
                                throw new IllegalStateException("room access revoked during authorization");
                            if (identity == null || !roomId.equals(identity.roomId()))
                                throw new IllegalStateException("backend returned mismatched room identity");
                            if (identity.expiresAt() <= System.currentTimeMillis())
                                throw new IllegalStateException("backend identity is expired");
                            session.lastValidatedAt.put(roomId, System.currentTimeMillis());
                            scheduleExpiry(session, roomId, identity.expiresAt());
                            scheduleAuthorizationRevalidation(session);
                        }
                        validation.complete(identity);
                    } catch (Throwable exception) {
                        terminateAuthorization(session, roomId, exception);
                        validation.completeExceptionally(exception);
                    }
                });
            } catch (Throwable exception) {
                terminateAuthorization(session, roomId, exception);
                validation.completeExceptionally(exception);
            }
            validation.whenComplete((identity, failure) -> {
                if (failure != null) terminateAuthorization(session, roomId, unwrap(failure));
            });
            return validation;
        }
    }

    /** Called by the Redis adapter. In-flight room checks are closed conservatively. */
    public void onRoomAccessRevoked(RoomAccessRevoked event) {
        if (event == null || !event.isValid()) return;
        for (SocketSession session : bus.sessionsSnapshot()) {
            boolean affected;
            synchronized (session) {
                if (session.closed || !session.channel.isActive()) continue;
                CompletableFuture<BackendBridge.Identity> identityFuture = session.identities.get(event.roomId().toString());
                if (identityFuture == null) continue;
                if (!identityFuture.isDone() || identityFuture.isCompletedExceptionally()) {
                    affected = true;
                } else {
                    BackendBridge.Identity identity = identityFuture.getNow(null);
                    affected = identity == null || event.userUuid().toString().equals(identity.userUuid());
                }
                if (affected) {
                    session.revokedRooms.add(event.roomId().toString());
                    session.closed = true;
                }
            }
            if (affected) terminateAuthorization(session, event.roomId().toString(), AuthorizationFailure.REVOKED);
        }
    }

    private void scheduleExpiry(SocketSession session, String roomId, long expiresAt) {
        Long existing = session.expiryAt.get(roomId);
        if (existing != null && existing == expiresAt && session.expiryTasks.containsKey(roomId)) return;
        io.netty.util.concurrent.ScheduledFuture<?> previous = session.expiryTasks.remove(roomId);
        if (previous != null) previous.cancel(false);
        session.expiryAt.put(roomId, expiresAt);
        long delay = Math.max(0, expiresAt - System.currentTimeMillis());
        io.netty.util.concurrent.ScheduledFuture<?> task = session.channel.eventLoop().schedule(() -> {
            if (session.closed) return;
            bus.error(session.channel, "access token expired");
            close(session);
            session.channel.close();
        }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
        session.expiryTasks.put(roomId, task);
    }

    private void scheduleAuthorizationRevalidation(SocketSession session) {
        synchronized (session) {
            if (session.closed || session.authorizationTask != null) return;
            long delayMs = nextAuthorizationRevalidationDelayMs();
            session.authorizationTask = session.channel.eventLoop().schedule(() -> {
                List<String> roomIds;
                synchronized (session) {
                    if (session.closed || !session.channel.isActive()) return;
                    session.authorizationTask = null;
                    roomIds = List.copyOf(session.identities.keySet());
                }
                for (String roomId : roomIds) {
                    synchronized (session) {
                        if (session.closed) break;
                        session.lastValidatedAt.put(roomId, 0L);
                    }
                    authorize(session, roomId);
                }
            }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    private long nextAuthorizationRevalidationDelayMs() {
        long min = properties.getAuthorizationRevalidationMin().toMillis();
        long max = properties.getAuthorizationRevalidationMax().toMillis();
        if (max == min) return min;
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    private void terminateAuthorization(SocketSession session, String roomId, Throwable cause) {
        terminateAuthorization(session, roomId, classifyAuthorizationFailure(cause), cause);
    }

    private void terminateAuthorization(SocketSession session, String roomId, AuthorizationFailure failure) {
        terminateAuthorization(session, roomId, failure, null);
    }

    private void terminateAuthorization(SocketSession session, String roomId, AuthorizationFailure failure, Throwable cause) {
        synchronized (session) {
            if (session.closeStarted || session.terminationStarted || !session.channel.isActive()) return;
            // Block inbound commands, revalidation, and OutboundBus writes before event-loop scheduling.
            session.closed = true;
            session.terminationStarted = true;
        }
        Runnable terminate = () -> {
            synchronized (session) {
                if (session.closeStarted || !session.channel.isActive()) return;
            }
            if (failure.temporary) {
                log.warn("Backend authorization temporarily unavailable; roomId={} errorType={}", roomId,
                        cause == null ? "RedisRevalidationFallback" : unwrap(cause).getClass().getSimpleName());
            } else if (cause != null) {
                int status = backendStatus(cause);
                log.info("Backend authorization denied; roomId={} status={}", roomId, status);
            } else {
                log.info("Room access revoked; roomId={}", roomId);
            }
            close(session);
            bus.errorAndClose(session.channel, failure.message, failure.closeCode, failure.closeReason);
        };
        try {
            if (session.channel.eventLoop().inEventLoop()) terminate.run();
            else session.channel.eventLoop().execute(terminate);
        } catch (RejectedExecutionException exception) {
            close(session);
            session.channel.close();
        }
    }

    private static AuthorizationFailure classifyAuthorizationFailure(Throwable failure) {
        int status = backendStatus(failure);
        if (status == 401 || status == 403 || (status >= 400 && status < 500 && status != 408 && status != 429))
            return AuthorizationFailure.DENIED;
        if (failure instanceof IllegalStateException) return AuthorizationFailure.DENIED;
        return AuthorizationFailure.TEMPORARY;
    }

    private static int backendStatus(Throwable failure) {
        Throwable cause = unwrap(failure);
        return cause instanceof BackendBridge.BackendStatusException status ? status.getStatusCode() : 0;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static void validateAuthorizationTiming(SocketProperties properties) {
        Duration ttl = properties.getAuthorizationCacheTtl();
        Duration min = properties.getAuthorizationRevalidationMin();
        Duration max = properties.getAuthorizationRevalidationMax();
        if (ttl == null || min == null || max == null || ttl.isZero() || ttl.isNegative()
                || min.isZero() || min.isNegative() || max.compareTo(min) < 0 || max.compareTo(ttl) > 0) {
            throw new IllegalArgumentException("Invalid realtime authorization cache/revalidation interval settings");
        }
    }

    private record AuthorizationFailure(String message, int closeCode, String closeReason, boolean temporary) {
        private static final AuthorizationFailure REVOKED =
                new AuthorizationFailure("room access revoked", 4403, "room access revoked", false);
        private static final AuthorizationFailure DENIED =
                new AuthorizationFailure("room authorization denied", 4403, "authorization denied", false);
        private static final AuthorizationFailure TEMPORARY =
                new AuthorizationFailure("authorization service unavailable", 1013, "authorization unavailable", true);
    }

    private CompletionStage<Void> writePong(SocketSession session) {
        if (!session.channel.isActive()) return CompletableFuture.completedFuture(null);
        session.channel.writeAndFlush(new io.netty.handler.codec.http.websocketx.TextWebSocketFrame(
                "{\"op\":\"PONG\"}"));
        return CompletableFuture.completedFuture(null);
    }

    private CompletionStage<Void> failed(SocketSession session, String message) {
        bus.error(session.channel, message);
        return CompletableFuture.completedFuture(null);
    }

    private static boolean validSubscriptionId(String id) {
        return id != null && !id.isBlank() && id.length() <= 80;
    }

    private static String normalizeUuid(String text) {
        try { return UUID.fromString(text).toString(); }
        catch (IllegalArgumentException exception) { return null; }
    }

    public void close(SocketSession session) {
        if (session == null) return;
        synchronized (session) {
            if (session.closeStarted) return;
            session.closed = true;
            session.closeStarted = true;
            if (session.authorizationTask != null) {
                session.authorizationTask.cancel(false);
                session.authorizationTask = null;
            }
            session.expiryTasks.values().forEach(task -> task.cancel(false));
            session.expiryTasks.clear();
        }
        session.tail.handle((ignored, failure) -> null).thenCompose(ignored -> {
            List<String> roomIds = new ArrayList<>(session.identities.keySet());
            String mafiaRoom = mafiaRooms.remove(session.id);
            CompletableFuture<?>[] cleanup = roomIds.stream()
                    .map(roomId -> queue.sync(roomId, () -> presence.onDisconnect(session, roomId)))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture<Void> all = CompletableFuture.allOf(cleanup);
            if (mafiaRoom == null) return all;
            return all.thenCompose(ignored2 -> queue.sync(mafiaRoom, () -> mafia.disconnect(session.id)));
        }).whenComplete((ignored, failure) -> {
            bus.close(session);
            pending.remove(session.id);
        });
    }
}
