package com.ee06.mychat.realtime.mafia.engine;

import com.ee06.mychat.realtime.mafia.entity.MafiaGame;
import com.ee06.mychat.realtime.mafia.entity.MafiaPlayer;
import com.ee06.mychat.realtime.mafia.dto.outbound.*;
import lombok.RequiredArgsConstructor;
import com.ee06.mychat.realtime.session.OutboundBus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single outbound gate for the mafia game. It builds ONLY public DTOs for
 * broadcasts (which structurally lack role/word), and sends secret role info
 * exclusively via convertAndSendToUser. Keeping all sends here makes the
 * "no secret leaks before RESULT" rule reviewable in one file.
 */
@Component
@RequiredArgsConstructor
public class MafiaBroadcaster {

    private final OutboundBus bus;

    private String topic(MafiaGame game, String suffix) {
        return "/ws/wooms/sketchmafia/" + suffix + "/" + game.getRoomId();
    }

    // ---- public broadcasts (no role/word) --------------------------------

    public void state(MafiaGame game) {
        state(game, null);
    }

    public void state(MafiaGame game, String message) {
        bus.publish(topic(game, "state"), buildState(game, message));
    }

    public void reveal(MafiaGame game) {
        List<RevealBroadcast.Entry> drawings = new ArrayList<>();
        for (MafiaPlayer p : game.getPlayers().values()) {
            if (p.getRole() == null) continue; // round participants only
            drawings.add(RevealBroadcast.Entry.builder()
                    .nickname(p.getNickname())
                    .pixels(p.getGrid())
                    .submitted(p.isSubmitted())
                    .build());
        }
        bus.publish(topic(game, "reveal"),
                RevealBroadcast.builder().size(MafiaGame.GRID_SIZE).drawings(drawings).build());
    }

    public void result(MafiaGame game) {
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (String target : game.getVotes().values()) {
            tally.merge(target, 1, Integer::sum);
        }
        List<ResultBroadcast.Role> roles = new ArrayList<>();
        for (MafiaPlayer p : game.getPlayers().values()) {
            if (p.getRole() == null) continue;
            roles.add(ResultBroadcast.Role.builder()
                    .nickname(p.getNickname())
                    .role(p.getRole().name())
                    .word(p.getWord())
                    .build());
        }
        bus.publish(topic(game, "result"), ResultBroadcast.builder()
                .topic(game.getTopicPair() != null ? game.getTopicPair().topic() : null)
                .decoy(game.getTopicPair() != null ? game.getTopicPair().decoy() : null)
                .category(game.getTopicPair() != null ? game.getTopicPair().category() : null)
                .mafiaNickname(game.getMafiaNickname())
                .accusedNickname(game.getAccusedNickname())
                .tie(game.isTie())
                .outcome(game.getOutcome() != null ? game.getOutcome().name() : null)
                .voteTally(tally)
                .votes(new LinkedHashMap<>(game.getVotes()))
                .scores(new LinkedHashMap<>(game.getScores()))
                .roles(roles)
                .build());
    }

    public void chat(MafiaGame game, String nickname, String message) {
        bus.publish(topic(game, "chat"),
                ChatBroadcast.builder().nickname(nickname).message(message).build());
    }

    // ---- private (per-user) sends ----------------------------------------

    public void role(MafiaPlayer player, MafiaGame game) {
        boolean mafia = player.isMafia();
        RoleAssignment payload = RoleAssignment.builder()
                .role(player.getRole().name())
                .word(player.getWord())
                .category(game.getTopicPair() != null ? game.getTopicPair().category() : null)
                .mafia(mafia)
                .note(mafia
                        ? "당신은 그림 마피아! 이 참고 단어로 자연스럽게 묻어가세요."
                        : "주제를 그려서 마피아를 가려내세요.")
                .build();
        sendToSession(player.getSessionId(), "/queue/sketchmafia/role", payload);
    }

    public void error(String sessionId, String message) {
        sendToSession(sessionId, "/queue/sketchmafia/error",
                ErrorMessage.builder().message(message).build());
    }

    private void sendToSession(String sessionId, String destination, Object payload) {
        bus.privateMessage(sessionId, destination, payload);
    }

    // ---- builders --------------------------------------------------------

    private GameStateBroadcast buildState(MafiaGame game, String message) {
        List<PlayerView> views = new ArrayList<>();
        for (MafiaPlayer p : game.getPlayers().values()) {
            views.add(PlayerView.builder()
                    .nickname(p.getNickname())
                    .costume(p.getCostume())
                    .connected(p.isConnected())
                    .host(p.getNickname().equals(game.getHostNickname()))
                    .submitted(p.isSubmitted())
                    .voted(p.getVotedFor() != null)
                    .score(game.getScores().getOrDefault(p.getNickname(), 0))
                    .build());
        }
        long endsAt = game.getPhaseEndsAtEpochMs();
        return GameStateBroadcast.builder()
                .phase(game.getPhase().name())
                .round(game.getRound())
                .players(views)
                .host(game.getHostNickname())
                .minPlayers(MafiaGame.MIN_PLAYERS)
                .maxPlayers(MafiaGame.MAX_PLAYERS)
                .endsAt(endsAt > 0 ? endsAt : null)
                .serverNow(System.currentTimeMillis())
                .message(message)
                .build();
    }
}
