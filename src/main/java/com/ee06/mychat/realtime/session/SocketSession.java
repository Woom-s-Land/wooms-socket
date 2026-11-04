package com.ee06.mychat.realtime.session;

import com.ee06.mychat.realtime.backend.BackendBridge;
import io.netty.channel.Channel;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Set;
import io.netty.util.concurrent.ScheduledFuture;

public final class SocketSession {
    public final Channel channel;
    public final String id;
    public final String cookie;
    public final ConcurrentMap<String, String> subscriptions = new ConcurrentHashMap<>();
    public final ConcurrentMap<String, CompletableFuture<BackendBridge.Identity>> identities = new ConcurrentHashMap<>();
    public final ConcurrentMap<String, Long> lastValidatedAt = new ConcurrentHashMap<>();
    public final ConcurrentMap<String, Long> expiryAt = new ConcurrentHashMap<>();
    public final ConcurrentMap<String, ScheduledFuture<?>> expiryTasks = new ConcurrentHashMap<>();
    public final Set<String> revokedRooms = ConcurrentHashMap.newKeySet();
    public volatile ScheduledFuture<?> authorizationTask;
    public volatile CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    public volatile boolean closed;
    public volatile boolean terminationStarted;
    public volatile boolean closeStarted;

    public SocketSession(Channel channel, String cookie) {
        this.channel = channel;
        this.id = channel.id().asLongText();
        this.cookie = cookie;
    }

}
