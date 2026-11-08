package com.ee06.mychat.realtime.room;

import com.ee06.mychat.realtime.config.SocketProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomQueueShutdownTest {

    @Test void runsSameRoomInSubmissionOrderAndRejectsAfterShutdown() throws Exception {
        RoomQueue queue = new RoomQueue(new SocketProperties());
        List<Integer> order = java.util.Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<Void> last = null;
        for (int i = 0; i < 50; i++) {
            int n = i;
            last = queue.sync("room", () -> order.add(n));
        }
        last.get(5, TimeUnit.SECONDS);
        assertEquals(java.util.stream.IntStream.range(0, 50).boxed().toList(), order);

        queue.shutdown();
        assertThrows(RejectedExecutionException.class, () -> queue.sync("room", () -> {}));
    }

    @Test void shutdownDuringFirstUseNeverLeavesARunningGroup() throws Exception {
        // Park the first run() inside group creation, then shut down concurrently.
        SocketProperties properties = spy(new SocketProperties());
        CountDownLatch creating = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> { creating.countDown(); release.await(); return 1; }).when(properties).getRoomThreads();
        RoomQueue queue = new RoomQueue(properties);

        CompletableFuture<CompletableFuture<Void>> submitted = CompletableFuture.supplyAsync(() -> queue.sync("room", () -> {}));
        assertTrue(creating.await(3, TimeUnit.SECONDS));
        Thread stopper = new Thread(queue::shutdown);
        stopper.start();
        Thread.sleep(100); // previously shutdown() finished here, before the group existed
        release.countDown();
        stopper.join(20_000);
        assertFalse(stopper.isAlive());

        try { submitted.get(5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS); } // normally drains during graceful shutdown
        catch (ExecutionException rejected) { assertInstanceOf(RejectedExecutionException.class, rejected.getCause()); }
        assertNull(executors(queue), "a group created after shutdown would leak its threads");
        assertThrows(RejectedExecutionException.class, () -> queue.sync("room", () -> {}));
    }

    private static Object executors(RoomQueue queue) throws Exception {
        Field field = RoomQueue.class.getDeclaredField("executors");
        field.setAccessible(true);
        synchronized (queue) { return field.get(queue); }
    }
}
