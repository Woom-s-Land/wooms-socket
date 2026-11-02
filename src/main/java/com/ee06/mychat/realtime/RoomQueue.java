package com.ee06.mychat.realtime;

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
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class RoomQueue {
    private static final int MAX_PENDING_PER_ROOM = 2048;
    private final SocketProperties properties;
    private final ConcurrentMap<String, Slot> slots = new ConcurrentHashMap<>();
    private volatile DefaultEventExecutorGroup executors;
    private volatile List<EventExecutor> roomExecutors = List.of();
    private volatile boolean closed;

    private DefaultEventExecutorGroup executors() {
        if (closed) throw new RejectedExecutionException("room queue is shut down");
        DefaultEventExecutorGroup current = executors;
        if (current == null) {
            synchronized (this) {
                if (closed) throw new RejectedExecutionException("room queue is shut down");
                current = executors;
                if (current == null) {
                    executors = current = new DefaultEventExecutorGroup(properties.getRoomThreads());
                    List<EventExecutor> children = new ArrayList<>();
                    Iterator<EventExecutor> iterator = current.iterator();
                    iterator.forEachRemaining(children::add);
                    roomExecutors = List.copyOf(children);
                }
            }
        }
        return current;
    }

    public CompletableFuture<Void> run(String roomId, Supplier<CompletionStage<Void>> action) {
        Slot slot = slots.computeIfAbsent(roomId, ignored -> new Slot());
        synchronized (slot) {
            if (slot.pending.get() >= MAX_PENDING_PER_ROOM) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("room queue capacity exceeded"));
            }
            slot.pending.incrementAndGet();
            CompletableFuture<Void> next = slot.tail.handle((ignored, previousError) -> null)
                    .thenComposeAsync(ignored -> {
                        try {
                            CompletionStage<Void> stage = action.get();
                            return stage == null ? CompletableFuture.completedFuture(null) : stage;
                        } catch (Throwable error) {
                            return CompletableFuture.failedFuture(error);
                        }
                    }, executorFor(roomId));
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
        executors();
        List<EventExecutor> children = roomExecutors;
        return children.get(Math.floorMod(roomId.hashCode(), children.size()));
    }

    @PreDestroy
    public void shutdown() {
        closed = true;
        DefaultEventExecutorGroup group = executors;
        executors = null;
        roomExecutors = List.of();
        if (group != null) group.shutdownGracefully().awaitUninterruptibly();
    }

    private static final class Slot {
        private final AtomicInteger pending = new AtomicInteger();
        private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    }
}
