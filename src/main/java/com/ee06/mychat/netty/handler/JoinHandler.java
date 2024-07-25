package com.ee06.mychat.netty.handler;

import com.ee06.mychat.domain.User;
import com.ee06.mychat.netty.ChannelRepository;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@ChannelHandler.Sharable
public class JoinHandler extends ChannelInboundHandlerAdapter {
    private final ChannelRepository channelRepository;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof String) || !((String) msg).startsWith("join ")) {
            ctx.fireChannelRead(msg);
            return;
        }
        String[] args = ((String) msg).split(" ");

        if (log.isDebugEnabled()) {
            log.debug((String)msg);
        }

        // join {서버명} {닉네임}
        User user = User.of(args[2] , ctx.channel(), args[1]);
        user.login(channelRepository, ctx.channel());

        ctx.writeAndFlush("Successfully logged in as " + user.getUsername() + ". \r\n");
    }
}



//1. 신규 방 개설 시
//
//private static final ConcurrentHashMap<String, ChannelGroup> roomsInfo = new ConcurrentHashMap<>();
//
//ChannelGroup channelGroup. = roomsInfo.putIfAbsent("방이름",
//                                                           new DefaultChannelGroup(ImmediateEventExecutor.INSTANCE);
// if(channelGroup == null){
//        roomsInfo.get("방이름").add(ctx.channel());
//        }
//
//        2. 룸에 메시지 전송 시
//ChannelGroup room = roomsInfo.get(방이름);
//  if(room !=null ) {
//        queueCounter.incrementAndGet();
//        room.writeAndFlush("메시지").addListener(f -> {
//        logger.info("send unsend message ={}", queueCounter.decrementAndGet();
//         }
//                 );
//                 }