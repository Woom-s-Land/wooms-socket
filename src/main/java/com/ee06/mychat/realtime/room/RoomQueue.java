package com.ee06.mychat.realtime.room;

import com.ee06.mychat.realtime.config.SocketProperties;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutor;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class RoomQueue {
    private static final int MAX_PENDING_PER_ROOM = 2048;
    private final SocketProperties properties;
    private final ConcurrentMap<String, Slot> slots = new ConcurrentHashMap<>();
    private DefaultEventExecutorGroup executors; // guarded by this
    private volatile List<EventExecutor> roomExecutors; // null until first use; never emptied
    private volatile boolean closed;

    // closed and executors change together under this monitor, so no group can be created after shutdown.
    private synchronized List<EventExecutor> start() {
        if (closed) throw new RejectedExecutionException("room queue is shut down");
        if (roomExecutors == null) {
            executors = new DefaultEventExecutorGroup(properties.getRoomThreads());
            List<EventExecutor> children = new ArrayList<>();
            executors.forEach(children::add);
            roomExecutors = List.copyOf(children);
        }
        return roomExecutors;
    }

    public CompletableFuture<Void> run(String roomId, Supplier<CompletionStage<Void>> action) {
        Slot slot = slots.computeIfAbsent(roomId, ignored -> new Slot());
        synchronized (slot) {
            if (slot.pending.get() >= MAX_PENDING_PER_ROOM) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("room queue capacity exceeded"));
            }
            EventExecutor executor = executorFor(roomId);
            slot.pending.incrementAndGet();
            CompletableFuture<Void> next = slot.tail.handle((ignored, previousError) -> null)
                    .thenComposeAsync(ignored -> {
                        try {
                            CompletionStage<Void> stage = action.get();
                            return stage == null ? CompletableFuture.completedFuture(null) : stage;
                        } catch (Throwable error) {
                            return CompletableFuture.failedFuture(error);
                        }
                    }, executor);
            slot.tail = next.whenComplete((ignored, error) -> slot.pending.decrementAndGet());
            return next;
        }
    }

    public CompletableFuture<Void> sync(String roomId, Runnable action) {
        return run(roomId, () -> {
            action.run();
            return CompletableFuture.completedFuture(null);
        });
    }

    private EventExecutor executorFor(String roomId) {
        List<EventExecutor> children = roomExecutors;
        if (children == null || closed) children = start();
        return children.get(Math.floorMod(roomId.hashCode(), children.size()));
    }

    @PreDestroy
    public void shutdown() {
        DefaultEventExecutorGroup group;
        synchronized (this) {
            closed = true;
            group = executors;
            executors = null;
        }
        // Await outside the monitor: a draining task may still call run() and must not block on it.
        if (group != null) group.shutdownGracefully().awaitUninterruptibly();
    }

    private static final class Slot {
        private final AtomicInteger pending = new AtomicInteger();
        private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    }
}
