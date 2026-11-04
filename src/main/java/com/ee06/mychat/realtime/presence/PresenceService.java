package com.ee06.mychat.realtime.presence;

import com.ee06.mychat.realtime.backend.BackendBridge;
import com.ee06.mychat.realtime.session.OutboundBus;
import com.ee06.mychat.realtime.session.SocketSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@RequiredArgsConstructor
public class PresenceService {
    private final OutboundBus bus;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ConcurrentMap<String, Map<String, Presence>> rooms = new ConcurrentHashMap<>();

    public void onSubscribe(SocketSession session, BackendBridge.Identity identity, String destination) {
        String roomId = identity.roomId();
        if (!("/ws/wooms/move/" + roomId).equals(destination)) return;

        Map<String, Presence> members = rooms.computeIfAbsent(roomId, ignored -> new LinkedHashMap<>());
        Presence previous = members.get(identity.userUuid());
        if (previous != null && !previous.sessionId.equals(session.id)) {
            SocketSession oldSession = bus.get(previous.sessionId);
            if (oldSession != null) oldSession.channel.close();
        }
        members.put(identity.userUuid(), new Presence(session.id, defaultPose(identity)));
        members.values().forEach(presence ->
                bus.privateMessage(session.id, "/user/queue/init", presence.pose));
    }

    public void send(SocketSession session, BackendBridge.Identity identity, String kind, JsonNode body) {
        String roomId = identity.roomId();
        if ("chat".equals(kind)) {
            JsonNode content = body == null ? null : body.get("content");
            if (content == null || !content.isTextual()
                    || content.asText().length() < 1 || content.asText().length() > 2000) {
                bus.error(session.channel, "invalid chat content");
                return;
            }
            bus.publish("/ws/wooms/chat/" + roomId, Map.of(
                    "content", content.asText(), "nickname", identity.nickname()));
            return;
        }

        if (!"move".equals(kind)) {
            bus.error(session.channel, "unknown event kind");
            return;
        }

        String x = finiteCoordinate(body, "x");
        String y = finiteCoordinate(body, "y");
        Integer direction = integer(body, "direction");
        Integer stepId = integer(body, "stepId");
        if (x == null || y == null || direction == null || stepId == null
                || direction < 0 || direction > 3) {
            bus.error(session.channel, "invalid move message");
            return;
        }

        ObjectNode pose = mapper.createObjectNode()
                .put("x", x)
                .put("y", y)
                .put("direction", direction)
                .put("stepId", stepId)
                .put("nickname", identity.nickname());
        if (identity.costume() == null) pose.putNull("costume");
        else pose.put("costume", identity.costume());

        Map<String, Presence> members = rooms.computeIfAbsent(roomId, ignored -> new LinkedHashMap<>());
        Presence presence = members.get(identity.userUuid());
        if (presence == null) {
            presence = new Presence(session.id, defaultPose(identity));
            members.put(identity.userUuid(), presence);
        } else if (!presence.sessionId.equals(session.id)) {
            bus.error(session.channel, "presence owned by another connection");
            return;
        }
        presence.pose = pose;
        bus.publish("/ws/wooms/move/" + roomId, pose);
    }

    public void onDisconnect(SocketSession session, String roomId) {
        Map<String, Presence> members = rooms.get(roomId);
        if (members == null) return;

        members.entrySet().removeIf(entry -> {
            Presence presence = entry.getValue();
            if (!presence.sessionId.equals(session.id)) return false;
            bus.publish("/ws/wooms/disconnect/" + roomId, presence.pose);
            return true;
        });
        if (members.isEmpty()) rooms.remove(roomId, members);
    }

    private ObjectNode defaultPose(BackendBridge.Identity identity) {
        ObjectNode pose = mapper.createObjectNode()
                .put("x", "1020")
                .put("y", "750")
                .put("direction", 0)
                .put("stepId", 0)
                .put("nickname", identity.nickname());
        if (identity.costume() == null) pose.putNull("costume");
        else pose.put("costume", identity.costume());
        return pose;
    }

    private static String finiteCoordinate(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || value.isNull() || !(value.isTextual() || value.isNumber())) return null;
        String normalized = value.asText();
        try {
            return Double.isFinite(Double.parseDouble(normalized)) ? normalized : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Integer integer(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || value.isNull()) return null;
        if (value.isIntegralNumber() && value.canConvertToInt()) return value.intValue();
        if (value.isTextual()) {
            try { return Integer.valueOf(value.textValue()); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private static final class Presence {
        private final String sessionId;
        private ObjectNode pose;

        private Presence(String sessionId, ObjectNode pose) {
            this.sessionId = sessionId;
            this.pose = pose;
        }
    }
}
