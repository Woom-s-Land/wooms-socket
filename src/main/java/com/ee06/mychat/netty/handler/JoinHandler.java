package com.ee06.mychat.netty.handler;

import com.ee06.mychat.domain.User;
import com.ee06.mychat.global.jwt.JWTUtil;
import com.ee06.mychat.netty.ChannelRepository;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Objects;

@Component
@Slf4j
@RequiredArgsConstructor
@ChannelHandler.Sharable
public class JoinHandler extends ChannelInboundHandlerAdapter {
    private final ChannelRepository channelRepository;
    private final JWTUtil jwtUtil;
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        super.channelRegistered(ctx);

        String url = ctx.channel().remoteAddress().toString();
        URI uri = new URI(url);
        log.info("url1 : {}", uri);
        log.info("url3 : {}", uri.getQuery());
//        if(Objects.isNull(uri.getQuery())) ctx.close();
//        String[] args = uri.getQuery().split("=");
//
//        String token = args[1];
//        if(!jwtUtil.isExpired(token) || !jwtUtil.validateToken(token)){
//                ctx.close();
//        }

        String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1dWlkIjoiMTIzNDU2Nzg5MCIsIm5pY2tuYW1lIjoic29uZ2RvIiwiY29zdHVtZSI6IjEiLCJjaGFubmVsVXVpZCI6IjEyMzI0MTI1MSJ9.F6fbC4PZPmwy_Y00A62Hm7yBiZMCOyl-BSypCNNkRrU";

        log.info(jwtUtil.getCostume(token));
        log.info(jwtUtil.getChannelUuid(token));
        log.info(jwtUtil.getUuid(token));
        log.info(jwtUtil.getNickname(token));

        User user = User.of(jwtUtil.getNickname(token) , ctx.channel(), jwtUtil.getChannelUuid(token), jwtUtil.getCostume(token));
        user.login(channelRepository, ctx.channel());

        User.current(ctx.channel()).join(channelRepository);
        ctx.writeAndFlush(user.getUsername() + "is joined.\r\n");
    }
}
