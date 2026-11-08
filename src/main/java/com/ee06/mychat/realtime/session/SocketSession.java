package com.ee06.mychat.realtime.session;

import com.ee06.mychat.realtime.backend.BackendBridge;
import io.netty.channel.Channel;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.HashSet;
import io.netty.util.concurrent.ScheduledFuture;

public final class SocketSession {
    public final Channel channel;
    public final String id;
    public final String cookie;
    public final ConcurrentMap<String, String> subscriptions = new ConcurrentHashMap<>();
    public final ConcurrentMap<String, CompletableFuture<BackendBridge.Identity>> identities = new ConcurrentHashMap<>();
    public final ConcurrentMap<String, AuthorizationAttempt> authorizationAttempts = new ConcurrentHashMap<>();
    /** Trusted principal survives per-room cache replacement and revalidation. Guarded by this session's monitor. */
    public String authenticatedUserUuid;
    public final ConcurrentMap<String, Long> lastValidatedAt = new ConcurrentHashMap<>();
    public final ConcurrentMap<String, Long> expiryAt = new ConcurrentHashMap<>();
    public final ConcurrentMap<String, ScheduledFuture<?>> expiryTasks = new ConcurrentHashMap<>();
    public final Set<String> revokedRooms = ConcurrentHashMap.newKeySet();
    public volatile ScheduledFuture<?> authorizationTask;
    public volatile CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    /** Inbound commands chained on tail but not yet finished; bounds per-connection backlog. */
    public final AtomicInteger pendingCommands = new AtomicInteger();
    public volatile boolean closed;
    public volatile boolean terminationStarted;
    public volatile boolean closeStarted;

    public SocketSession(Channel channel, String cookie) {
        this.channel = channel;
        this.id = channel.id().asLongText();
        this.cookie = cookie;
    }

    public static final class AuthorizationAttempt {
        public final CompletableFuture<BackendBridge.Identity> future = new CompletableFuture<>();
        /** Mutated only while holding the owning SocketSession monitor. */
        public final Set<String> revokedUserUuids = new HashSet<>();
    }

}
