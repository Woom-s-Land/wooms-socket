package com.ee06.mychat.realtime.access.event;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonFormat;

/** Redis contract mirrored by backend; contains identifiers only, never credentials. */
public record RoomAccessRevoked(
        int schemaVersion,
        UUID eventId,
        String type,
        UUID roomId,
        UUID userUuid,
        String reason,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String TYPE = "ROOM_ACCESS_REVOKED";

    @JsonIgnore
    public boolean isValid() {
        return schemaVersion == CURRENT_SCHEMA_VERSION && eventId != null && TYPE.equals(type)
                && roomId != null && userUuid != null && occurredAt != null
                && reason != null && !reason.isBlank() && reason.length() <= 64;
    }
}
