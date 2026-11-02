package com.ee06.mychat.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class SketchService {
    private static final int SIZE = 32;
    private static final int EMPTY = -1;
    private final OutboundBus bus;
    private final BackendBridge bridge;
    private final RoomQueue roomQueue;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ConcurrentMap<String, Wall> walls = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "sketch-wall-flush"); thread.setDaemon(true); return thread;
    });

    public SketchService(OutboundBus bus, BackendBridge bridge, RoomQueue roomQueue) {
        this.bus = bus;
        this.bridge = bridge;
        this.roomQueue = roomQueue;
        scheduler.scheduleAtFixedRate(this::scheduleDirtyFlushes, 5, 5, TimeUnit.SECONDS);
    }

    public CompletionStage<Void> onSubscribe(SocketSession session, BackendBridge.Identity identity) {
        return load(identity.roomId(), session.cookie).thenAccept(wall ->
                bus.privateMessage(session.id, "/user/queue/sketch/init",
                        Map.of("size", SIZE, "pixels", snapshot(wall))));
    }

    public CompletionStage<Void> send(SocketSession session, BackendBridge.Identity identity,
                                      String kind, JsonNode body) {
        return load(identity.roomId(), session.cookie).thenCompose(wall -> {
            if ("sketch/draw".equals(kind)) return draw(identity, wall, body);
            if ("sketch/save".equals(kind)) return archive(identity, session.cookie, wall);
            bus.error(session.channel, "unsupported sketch operation");
            return CompletableFuture.completedFuture(null);
        });
    }

    private CompletionStage<Wall> load(String roomId, String cookie) {
        Wall existing = walls.get(roomId);
        if (existing != null && existing.pixels != null) { synchronized (existing) { existing.cookie = cookie; } return CompletableFuture.completedFuture(existing); }
        Wall wall = walls.computeIfAbsent(roomId, ignored -> new Wall());
        synchronized (wall) {
            if (wall.load != null) return wall.load;
            wall.cookie = cookie;
            wall.load = bridge.wall(roomId, cookie).thenApply(json -> {
                int[][] pixels = parseGrid(json == null ? null : json.get("pixels"));
                synchronized (wall) { wall.pixels = pixels; }
                return wall;
            }).whenComplete((ignored, error) -> {
                synchronized (wall) { wall.load = null; }
            });
            return wall.load;
        }
    }

    private CompletionStage<Void> draw(BackendBridge.Identity identity, Wall wall, JsonNode body) {
        String tool = text(body, "tool");
        Integer x = integer(body, "x"), y = integer(body, "y");
        Integer color = integer(body, "colorIndex");
        if (!(("PEN".equals(tool) || "ERASE".equals(tool)) && inGrid(x) && inGrid(y)
                && ("ERASE".equals(tool) || color != null && color >= 0 && color <= 11))) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid sketch stroke"));
        }
        synchronized (wall) {
            wall.pixels[y][x] = "ERASE".equals(tool) ? EMPTY : color;
            wall.dirty = true;
        }
        ObjectNode stroke = mapper.createObjectNode().put("tool", tool).put("x", x).put("y", y)
                .put("nickname", identity.nickname());
        if (color != null) stroke.put("colorIndex", color);
        bus.publish("/ws/wooms/sketch/draw/" + identity.roomId(), stroke);
        return CompletableFuture.completedFuture(null);
    }

    private CompletionStage<Void> archive(BackendBridge.Identity identity, String cookie, Wall wall) {
        int[][] pixels = snapshot(wall);
        if (isBlank(pixels)) return CompletableFuture.failedFuture(new IllegalArgumentException("cannot archive an empty canvas"));
        return bridge.archive(identity.roomId(), cookie, pixels).thenAccept(ignored -> {
            synchronized (wall) { wall.pixels = blank(); wall.dirty = false; wall.cookie = cookie; }
            bus.publish("/ws/wooms/sketch/draw/" + identity.roomId(), Map.of("tool", "RESET"));
        });
    }

    private void scheduleDirtyFlushes() {
        walls.forEach((roomId, wall) -> {
            if (!wall.flushQueued.compareAndSet(false, true)) return;
            roomQueue.run(roomId, () -> {
                final String cookie; final int[][] pixels;
                synchronized (wall) {
                    if (!wall.dirty || wall.pixels == null) return CompletableFuture.completedFuture(null);
                    cookie = wall.cookie; pixels = copy(wall.pixels);
                }
                return bridge.putWall(roomId, cookie, pixels).thenRun(() -> {
                    synchronized (wall) { wall.dirty = false; }
                });
            }).whenComplete((ignored, error) -> {
                if (error != null) log.warn("Sketch wall flush failed for room {}", roomId);
                wall.flushQueued.set(false);
            });
        });
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        CompletableFuture<?>[] futures = walls.entrySet().stream().map(entry -> {
            String roomId = entry.getKey(); Wall wall = entry.getValue();
            return roomQueue.run(roomId, () -> {
                final String cookie; final int[][] pixels;
                synchronized (wall) {
                    if (!wall.dirty || wall.pixels == null) return CompletableFuture.completedFuture(null);
                    cookie = wall.cookie; pixels = copy(wall.pixels);
                }
                return bridge.putWall(roomId, cookie, pixels).thenRun(() -> {
                    synchronized (wall) { wall.dirty = false; }
                });
            }).whenComplete((ignored, error) -> { if (error != null) log.warn("Sketch wall shutdown flush failed for room {}", roomId); });
        }).toArray(CompletableFuture[]::new);
        try { CompletableFuture.allOf(futures).get(Math.max(0, deadline-System.nanoTime()), TimeUnit.NANOSECONDS); }
        catch (Exception error) { log.warn("Sketch wall shutdown flush did not finish before deadline"); }
    }

    private static int[][] parseGrid(JsonNode pixelsNode) {
        if (pixelsNode == null || !pixelsNode.isArray() || pixelsNode.size() != SIZE) throw new IllegalStateException("Invalid backend sketch grid");
        int[][] result = new int[SIZE][SIZE];
        for (int row = 0; row < SIZE; row++) {
            JsonNode cells = pixelsNode.get(row);
            if (cells == null || !cells.isArray() || cells.size() != SIZE) throw new IllegalStateException("Invalid backend sketch row");
            for (int col = 0; col < SIZE; col++) {
                JsonNode value = cells.get(col);
                if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < EMPTY || value.intValue() > 11) throw new IllegalStateException("Invalid backend sketch pixel");
                result[row][col] = value.intValue();
            }
        }
        return result;
    }

    private static int[][] snapshot(Wall wall) { synchronized (wall) { return copy(wall.pixels == null ? blank() : wall.pixels); } }
    private static int[][] copy(int[][] source) { return Arrays.stream(source).map(int[]::clone).toArray(int[][]::new); }
    private static int[][] blank() { int[][] pixels = new int[SIZE][SIZE]; for (int[] row : pixels) Arrays.fill(row, EMPTY); return pixels; }
    private static boolean isBlank(int[][] pixels) { return Arrays.stream(pixels).allMatch(row -> Arrays.stream(row).allMatch(v -> v == EMPTY)); }
    private static boolean inGrid(Integer value) { return value != null && value >= 0 && value < SIZE; }
    private static Integer integer(JsonNode node, String field) { JsonNode v=node==null?null:node.get(field); return v!=null&&v.isIntegralNumber()&&v.canConvertToInt()?v.intValue():null; }
    private static String text(JsonNode node, String field) { JsonNode v=node==null?null:node.get(field); return v!=null&&v.isTextual()?v.asText():null; }

    private static final class Wall {
        private int[][] pixels;
        private boolean dirty;
        private String cookie;
        private CompletableFuture<Wall> load;
        private final AtomicBoolean flushQueued = new AtomicBoolean();
    }
}
