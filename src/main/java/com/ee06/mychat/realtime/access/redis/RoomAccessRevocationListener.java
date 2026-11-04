package com.ee06.mychat.realtime.access.redis;

import com.ee06.mychat.realtime.access.event.RoomAccessRevoked;
import com.ee06.mychat.realtime.access.port.RoomAccessRevocationHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class RoomAccessRevocationListener implements MessageListener {
    private static final int MAX_REMEMBERED_EVENT_IDS = 4096;
    private final RoomAccessRevocationHandler handler;
    private final ObjectMapper mapper;
    private final Map<UUID, Boolean> seenEventIds = new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
            return size() > MAX_REMEMBERED_EVENT_IDS;
        }
    };

    public RoomAccessRevocationListener(RoomAccessRevocationHandler handler, ObjectMapper mapper) {
        this.handler = handler;
        this.mapper = mapper;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        final RoomAccessRevoked event;
        try {
            event = mapper.readValue(message.getBody(), RoomAccessRevoked.class);
        } catch (Exception exception) {
            log.warn("Discarded malformed room access revocation message");
            return;
        }
        if (event == null || !event.isValid()) {
            log.warn("Discarded invalid room access revocation message");
            return;
        }
        synchronized (seenEventIds) {
            if (seenEventIds.containsKey(event.eventId())) return;
            seenEventIds.put(event.eventId(), Boolean.TRUE);
        }
        handler.onRoomAccessRevoked(event);
    }
}
