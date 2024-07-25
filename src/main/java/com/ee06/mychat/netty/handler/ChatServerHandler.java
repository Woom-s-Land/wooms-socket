package com.ee06.mychat.netty.handler;

import com.ee06.mychat.domain.User;
import com.ee06.mychat.netty.ChannelRepository;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Component
@Slf4j
@RequiredArgsConstructor
@ChannelHandler.Sharable
public class ChatServerHandler extends ChannelInboundHandlerAdapter {
    private final ChannelRepository channelRepository;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        Assert.notNull(this.channelRepository, "[Assertion failed] - ChannelRepository is required; it must not be null");

        ctx.fireChannelActive();
        if (log.isDebugEnabled()) {
            log.debug(ctx.channel().remoteAddress() + "");
        }
        String remoteAddress = ctx.channel().remoteAddress().toString();

        ctx.writeAndFlush("Your remote address is " + remoteAddress + ".\r\n");

        if (log.isDebugEnabled()) {
            log.debug("Bound Channel Count is {}", this.channelRepository.size());
        }

    }
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        String stringMessage = (String) msg;
        if (log.isDebugEnabled()) {
            log.debug(stringMessage);
        }
//        log.info("msg1 : {}", msg);
        if ( stringMessage.startsWith("join ")) {
            ctx.fireChannelRead(msg);
            return;
        }
//        log.info("msg2 : {}", msg);

        String[] splitMessage = stringMessage.split(" ");

        if (splitMessage.length != 2) {
            log.info("msg3 : {}", msg);
            User.current(ctx.channel()).broadcast(channelRepository, msg);
//            ctx.channel().writeAndFlush(stringMessage + "\n\r");
        }

    }
}
