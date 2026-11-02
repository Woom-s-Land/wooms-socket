package com.ee06.mychat.realtime;

import io.netty.channel.Channel;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
    public volatile ScheduledFuture<?> authorizationTask;
    public volatile CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    public volatile boolean closed;

    public SocketSession(Channel channel, String cookie) {
        this.channel = channel;
        this.id = channel.id().asLongText();
        this.cookie = cookie;
    }
}
