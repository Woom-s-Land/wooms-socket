package com.ee06.mychat.realtime.sketch;

import com.ee06.mychat.realtime.backend.BackendBridge;
import com.ee06.mychat.realtime.config.SocketProperties;
import com.ee06.mychat.realtime.room.RoomQueue;
import com.ee06.mychat.realtime.session.OutboundBus;
import com.ee06.mychat.realtime.session.SocketSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SketchServiceRevocationRaceTest {
    private static final String ROOM = "00000000-0000-0000-0000-000000000001";

    @Test void delayedWallLoadCannotDrawOrArchiveAfterSessionWasRevoked() throws Exception {
        for (String operation : new String[]{"sketch/draw", "sketch/save"}) {
            OutboundBus bus = new OutboundBus();
            BackendBridge bridge = mock(BackendBridge.class);
            SocketProperties properties = new SocketProperties();
            RoomQueue queue = new RoomQueue(properties);
            SketchService sketch = new SketchService(bus, bridge, queue);
            EmbeddedChannel channel = new EmbeddedChannel(DefaultChannelId.newInstance());
            SocketSession session = bus.open(channel, "cookie-not-used");
            CompletableFuture<com.fasterxml.jackson.databind.JsonNode> wall = new CompletableFuture<>();
            when(bridge.wall(eq(ROOM), any())).thenReturn(wall);
            bus.subscribe(session, "draw", "/ws/wooms/sketch/draw/" + ROOM);
            BackendBridge.Identity identity = new BackendBridge.Identity(ROOM, "user", "nick", 1,
                    System.currentTimeMillis() + 30_000);
            var body = new ObjectMapper().createObjectNode()
                    .put("tool", "PEN").put("x", 1).put("y", 2).put("colorIndex", 3);

            CompletionStage<Void> pending = sketch.send(session, identity, operation, body);
            synchronized (session) { session.closed = true; }
            wall.complete(grid());
            pending.toCompletableFuture().join();

            verify(bridge, never()).archive(anyString(), anyString(), any(int[][].class));
            assertNull(channel.readOutbound());
            sketch.shutdown();
            bus.close(session);
            channel.finishAndReleaseAll();
            queue.shutdown();
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode grid() {
        var mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        var pixels = root.putArray("pixels");
        for (int row = 0; row < 32; row++) {
            var cells = pixels.addArray();
            for (int col = 0; col < 32; col++) cells.add(row == 0 && col == 0 ? 1 : -1);
        }
        return root;
    }
}
