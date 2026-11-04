package com.ee06.mychat.realtime.mafia.engine;

import com.ee06.mychat.realtime.room.RoomQueue;
import com.ee06.mychat.realtime.mafia.common.MafiaTimings;
import com.ee06.mychat.realtime.mafia.entity.MafiaPlayer;
import com.ee06.mychat.realtime.mafia.repository.MafiaGameRepository;
import com.ee06.mychat.realtime.mafia.topic.TopicService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MafiaJoinOwnershipTest {
    private final TopicService topicService = mock(TopicService.class);
    private final MafiaGameRepository repository = new MafiaGameRepository(topicService);
    private final MafiaBroadcaster broadcaster = mock(MafiaBroadcaster.class);
    private final MafiaGameEngine engine = new MafiaGameEngine(repository, broadcaster, topicService,
            mock(MafiaTimings.class), mock(ThreadPoolTaskScheduler.class), mock(RoomQueue.class));
    private final String room = "room-owner-test";

    @BeforeEach void resetRoom() { repository.remove(room); }

    @Test void differentUserCannotTakeNicknameOrReceiveExistingRole() {
        engine.join(room, "session-owner", "user-A", "same-name", 1);
        MafiaPlayer original = repository.get(room).getPlayers().get("same-name");
        original.setRole(com.ee06.mychat.realtime.mafia.entity.PlayerRole.MAFIA);
        original.setWord("secret-word");

        engine.join(room, "session-attacker", "user-B", "same-name", 2);

        assertEquals("user-A", original.getUserUuid());
        assertEquals("session-owner", original.getSessionId());
        assertEquals("room-owner-test", repository.roomOfSession("session-owner"));
        assertNull(repository.roomOfSession("session-attacker"));
        verify(broadcaster).error("session-attacker", "이미 사용 중인 닉네임이에요.");
        verify(broadcaster, never()).role(any(), any());
    }

    @Test void sameUserCanReconnectAndReceiveItsExistingRole() {
        engine.join(room, "session-old", "user-A", "same-name", 1);
        MafiaPlayer original = repository.get(room).getPlayers().get("same-name");
        original.setRole(com.ee06.mychat.realtime.mafia.entity.PlayerRole.MAFIA);
        original.setWord("secret-word");

        engine.join(room, "session-new", "user-A", "same-name", 9);

        assertEquals(1, repository.get(room).getPlayers().size());
        assertEquals("session-new", original.getSessionId());
        assertEquals("user-A", original.getUserUuid());
        assertEquals("room-owner-test", repository.roomOfSession("session-new"));
        verify(broadcaster).role(original, repository.get(room));
        verify(broadcaster, never()).error("session-new", "이미 사용 중인 닉네임이에요.");
    }

    @Test void sameUserCannotJoinAgainUnderAnotherNickname() {
        engine.join(room, "session-old", "user-A", "first-name", 1);
        engine.join(room, "session-next", "user-A", "second-name", 1);

        Map<String, MafiaPlayer> players = repository.get(room).getPlayers();
        assertEquals(1, players.size());
        assertTrue(players.containsKey("first-name"));
        assertNull(repository.roomOfSession("session-next"));
        verify(broadcaster).error("session-next", "이미 이 방에 참가한 계정이에요.");
    }
}
