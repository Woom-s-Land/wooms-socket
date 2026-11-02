package com.ee06.mychat.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.ee06.mychat.realtime.mafia.engine.MafiaGameEngine;

@Slf4j
@Component
public class NativeHub {
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
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Map<String, AtomicInteger> pending = new ConcurrentHashMap<>();
    private final Map<String, String> mafiaRooms = new ConcurrentHashMap<>();

    public NativeHub(OutboundBus bus, BackendBridge bridge, RoomQueue queue, PresenceService presence,
                     SketchService sketch, MafiaGameEngine mafia) {
        this.bus = bus;
        this.bridge = bridge;
        this.queue = queue;
        this.presence = presence;
        this.sketch = sketch;
        this.mafia = mafia;
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
            if (session.closed || !session.channel.isActive()) return CompletableFuture.completedFuture(null);
            bus.subscribe(session, id, destination.replace(matcher.group(2), roomId));
            if ("move".equals(kind)) presence.onSubscribe(session, identity, "/ws/wooms/move/" + roomId);
            if ("sketch/draw".equals(kind)) return sketch.onSubscribe(session, identity);
            return CompletableFuture.completedFuture(null);
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
            if (session.closed || !session.channel.isActive()) return CompletableFuture.completedFuture(null);
            if ("sketch/draw".equals(kind) || "sketch/save".equals(kind))
                return sketch.send(session, identity, kind, payload);
            if (kind.startsWith("sketchmafia/")) return mafiaCommand(session, identity, roomId, kind, payload);
            if ("chat".equals(kind) || "move".equals(kind)) {
                presence.send(session, identity, kind, payload);
                return CompletableFuture.completedFuture(null);
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
        long now = System.currentTimeMillis();
        synchronized (session) {
            if (session.closed || !session.channel.isActive()) return CompletableFuture.failedFuture(new IllegalStateException("session closed"));
            CompletableFuture<BackendBridge.Identity> current = session.identities.get(roomId);
            Long validatedAt = session.lastValidatedAt.get(roomId);
            if (current != null && !current.isDone()) return current;
            if (current != null && current.isCompletedExceptionally()) {
                session.identities.remove(roomId, current);
                current = null;
            }
            if (current != null && validatedAt != null && now - validatedAt < 5000) {
                BackendBridge.Identity cached = current.getNow(null);
                if (cached != null && cached.expiresAt() > now) return current;
                if (cached != null) {
                    bus.error(session.channel, "access token expired");
                    close(session);
                    session.channel.close();
                    return CompletableFuture.failedFuture(new IllegalStateException("access token expired"));
                }
            }

            CompletableFuture<BackendBridge.Identity> validation = bridge.authorize(roomId, session.cookie)
                    .thenApply(identity -> {
                        synchronized (session) {
                            if (session.closed || !session.channel.isActive())
                                throw new IllegalStateException("session closed");
                            if (identity == null || !roomId.equals(identity.roomId()))
                                throw new IllegalStateException("backend returned mismatched room identity");
                            if (identity.expiresAt() <= System.currentTimeMillis())
                                throw new IllegalStateException("backend identity is expired");
                            session.lastValidatedAt.put(roomId, System.currentTimeMillis());
                            scheduleExpiry(session, roomId, identity.expiresAt());
                            scheduleAuthorizationRevalidation(session);
                            return identity;
                        }
                    });
            session.identities.put(roomId, validation);
            validation.whenComplete((identity, failure) -> {
                if (failure != null) {
                    bus.error(session.channel, "room authorization failed");
                    close(session);
                    session.channel.close();
                }
            });
            return validation;
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
            session.authorizationTask = session.channel.eventLoop().scheduleAtFixedRate(() -> {
                if (session.closed || !session.channel.isActive()) return;
                for (String roomId : List.copyOf(session.identities.keySet())) {
                    // Bypass the five-second request cache while sharing any in-flight authorization future.
                    session.lastValidatedAt.put(roomId, 0L);
                    authorize(session, roomId);
                }
            }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
        }
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
            if (session.closed) return;
            session.closed = true;
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
