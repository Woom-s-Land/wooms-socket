package com.ee06.mychat.realtime.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
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

    public List<SocketSession> sessionsSnapshot() { return List.copyOf(sessions.values()); }

    public void subscribe(SocketSession session, String id, String destination) {
        synchronized (session) {
            if (session.closed || !session.channel.isActive()) return;
            String previous = session.subscriptions.put(id, destination);
            if (previous != null) removeIndex(session, id, previous);
            subscribers.compute(destination, (key, indexed) -> {
                Set<Subscriber> set = indexed == null ? ConcurrentHashMap.newKeySet() : indexed;
                set.add(new Subscriber(session.id, id));
                return set;
            });
        }
    }

    public void unsubscribe(SocketSession session, String id) {
        String destination = session.subscriptions.remove(id);
        if (destination != null) removeIndex(session, id, destination);
    }

    public void close(SocketSession session) {
        if (session == null) return;
        synchronized (session) {
            session.closed = true;
            sessions.remove(session.id, session);
            session.subscriptions.forEach((id, destination) -> removeIndex(session, id, destination));
            session.subscriptions.clear();
            session.identities.clear();
        }
    }

    public void publish(String destination, Object body) {
        Set<Subscriber> targets = subscribers.get(destination);
        if (targets == null) return;
        for (Subscriber target : targets) {
            SocketSession session = sessions.get(target.sessionId());
            if (session != null && destination.equals(session.subscriptions.get(target.subscriptionId())))
                deliver(session, target.subscriptionId(), destination, body);
        }
    }

    public void privateMessage(String sessionId, String destination, Object body) {
        SocketSession session = sessions.get(sessionId);
        if (session == null) return;
        String normalized = destination.startsWith("/queue/") ? "/user" + destination : destination;
        session.subscriptions.forEach((subscriptionId, subscribedDestination) -> {
            if (normalized.equals(subscribedDestination) || destination.equals(subscribedDestination)) {
                deliver(session, subscriptionId, subscribedDestination, body);
            }
        });
    }

    public void error(Channel channel, String message) {
        write(channel, Map.of("op", "ERROR", "message", message));
    }

    public void errorAndClose(Channel channel, String message, int closeCode, String closeReason) {
        if (channel == null || !channel.isActive()) return;
        if (!channel.isWritable()) {
            channel.close();
            return;
        }
        try {
            String error = mapper.writeValueAsString(Map.of("op", "ERROR", "message", message));
            channel.writeAndFlush(new TextWebSocketFrame(error)).addListener(sent -> {
                if (!sent.isSuccess() || !channel.isActive()) {
                    channel.close();
                    return;
                }
                channel.writeAndFlush(new CloseWebSocketFrame(closeCode, closeReason))
                        .addListener(ignored -> channel.close());
            });
        } catch (Exception exception) {
            channel.close();
        }
    }

    /**
     * The session monitor is the write/revocation linearization point. A write already
     * enqueued before closed is set may still reach the peer; no later write can pass.
     */
    private void deliver(SocketSession session, String subscriptionId, String destination, Object body) {
        if (session.closed) return;
        try {
            String encodedBody = body instanceof String string ? string : mapper.writeValueAsString(body);
            // Never acquire a recipient's monitor while a publisher's monitor is held.
            // The event-loop task rechecks admission immediately before enqueueing a frame.
            session.channel.eventLoop().execute(() -> {
                synchronized (session) {
                    if (session.closed || !session.channel.isActive()
                            || !destination.equals(session.subscriptions.get(subscriptionId))) return;
                    write(session.channel, Map.of("op", "MESSAGE", "subscription", subscriptionId,
                            "destination", destination, "body", encodedBody));
                }
            });
        } catch (Exception exception) {
            error(session.channel, "message delivery failed");
        }
    }

    private void write(Channel channel, Object envelope) {
        if (!channel.isActive()) return;
        if (!channel.isWritable()) {
            channel.close();
            return;
        }
        try {
            channel.writeAndFlush(new TextWebSocketFrame(mapper.writeValueAsString(envelope)))
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
