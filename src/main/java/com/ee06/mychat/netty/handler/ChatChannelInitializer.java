/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ee06.mychat.netty.handler;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Channel Initializer
 *
 * @author Jibeom Jung akka. Manty
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatChannelInitializer extends ChannelInitializer<SocketChannel> {
    private final ChatServerHandler chatServerHandler;
    private final JoinHandler joinHandler;
    private final StringEncoder stringEncoder = new StringEncoder();
    private final StringDecoder stringDecoder = new StringDecoder();

    @Override
    protected void initChannel(SocketChannel socketChannel) {
        ChannelPipeline pipeline = socketChannel.pipeline();
        log.info("server start???");
        // Add the text line codec combination first,
        pipeline.addLast(new DelimiterBasedFrameDecoder(1024 * 1024, Delimiters.lineDelimiter()));

        pipeline.addLast(stringDecoder);
        pipeline.addLast(stringEncoder);
        pipeline.addLast(chatServerHandler);
        pipeline.addLast(joinHandler);
    }
}
