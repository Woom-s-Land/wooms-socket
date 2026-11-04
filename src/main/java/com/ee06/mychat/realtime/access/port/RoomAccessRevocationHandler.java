package com.ee06.mychat.realtime.access.port;

import com.ee06.mychat.realtime.access.event.RoomAccessRevoked;

/** Transport-independent entry point for committed access revocations. */
public interface RoomAccessRevocationHandler {
    void onRoomAccessRevoked(RoomAccessRevoked event);
}
