package com.ee06.mychat.realtime.mafia.dto.outbound;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ChatBroadcast {
    private String nickname;
    private String message;
}
