package com.ee06.mychat.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Component
public class OutboundBus {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ConcurrentMap<String, SocketSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<Subscriber>> subscribers = new ConcurrentHashMap<>();

    public SocketSession open(Channel channel, String cookie) {
        SocketSession session = new SocketSession(channel, cookie);
        SocketSession replaced = sessions.put(session.id, session);
        if (replaced != null) close(replaced);
        return session;
    }

    public SocketSession get(String id) { return sessions.get(id); }

    public void subscribe(SocketSession session, String id, String destination) {
        String previous = session.subscriptions.put(id, destination);
        if (previous != null) removeIndex(session, id, previous);
        subscribers.compute(destination, (key, indexed) -> {
            Set<Subscriber> set = indexed == null ? ConcurrentHashMap.newKeySet() : indexed;
            set.add(new Subscriber(session.id, id));
            return set;
        });
    }

    public void unsubscribe(SocketSession session, String id) {
        String destination = session.subscriptions.remove(id);
        if (destination != null) removeIndex(session, id, destination);
    }

    public void close(SocketSession session) {
        if (session == null) return;
        session.closed = true;
        sessions.remove(session.id, session);
        session.subscriptions.forEach((id, destination) -> removeIndex(session, id, destination));
        session.subscriptions.clear();
        session.identities.clear();
    }

    public void publish(String destination, Object body) {
        Set<Subscriber> targets = subscribers.get(destination);
        if (targets == null) return;
        for (Subscriber target : targets) {
            SocketSession session = sessions.get(target.sessionId());
            if (session != null && destination.equals(session.subscriptions.get(target.subscriptionId()))) {
                deliver(session.channel, target.subscriptionId(), destination, body);
            }
        }
    }

    public void privateMessage(String sessionId, String destination, Object body) {
        SocketSession session = sessions.get(sessionId);
        if (session == null) return;
        String normalized = destination.startsWith("/queue/") ? "/user" + destination : destination;
        session.subscriptions.forEach((subscriptionId, subscribedDestination) -> {
            if (normalized.equals(subscribedDestination) || destination.equals(subscribedDestination)) {
                deliver(session.channel, subscriptionId, subscribedDestination, body);
            }
        });
    }

    public void error(Channel channel, String message) {
        write(channel, Map.of("op", "ERROR", "message", message));
    }

    private void deliver(Channel channel, String subscriptionId, String destination, Object body) {
        try {
            String encodedBody = body instanceof String string ? string : mapper.writeValueAsString(body);
            write(channel, Map.of("op", "MESSAGE", "subscription", subscriptionId,
                    "destination", destination, "body", encodedBody));
        } catch (Exception exception) {
            error(channel, "message serialization failed");
        }
    }

    private void write(Channel channel, Object envelope) {
        if (!channel.isActive()) return;
        if (!channel.isWritable()) {
            channel.close();
            return;
        }
        try {
            channel.writeAndFlush(new io.netty.handler.codec.http.websocketx.TextWebSocketFrame(mapper.writeValueAsString(envelope)))
                    .addListener(future -> { if (!future.isSuccess()) channel.close(); });
        } catch (Exception exception) {
            channel.close();
        }
    }

    private void removeIndex(SocketSession session, String id, String destination) {
        subscribers.computeIfPresent(destination, (key, set) -> {
            set.remove(new Subscriber(session.id, id));
            return set.isEmpty() ? null : set;
        });
    }

    private record Subscriber(String sessionId, String subscriptionId) {}
}
