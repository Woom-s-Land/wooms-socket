package com.ee06.mychat.realtime;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(SocketProperties.class)
public class RealtimeServer implements SmartLifecycle {
    private static final AttributeKey<String> COOKIE = AttributeKey.valueOf("requestCookie");
    private static final long WRITE_LOW_WATER_MARK = 64 * 1024L;
    private static final long WRITE_HIGH_WATER_MARK = 256 * 1024L;

    private final SocketProperties properties;
    private final NativeHub hub;
    private final ChannelGroup children = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private volatile EventLoopGroup boss;
    private volatile EventLoopGroup workers;
    private volatile Channel serverChannel;
    private volatile boolean running;

    @Override
    public synchronized void start() {
        if (running) return;
        if (properties.getStorageSecret() == null || properties.getStorageSecret().length() < 32) {
            throw new IllegalStateException("WOOMS_REALTIME_INTERNAL_SECRET must be configured with at least 32 characters");
        }
        boss = new NioEventLoopGroup(1, new DefaultThreadFactory("realtime-boss"));
        workers = new NioEventLoopGroup(4, new DefaultThreadFactory("realtime-worker"));
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                            new WriteBufferWaterMark((int) WRITE_LOW_WATER_MARK, (int) WRITE_HIGH_WATER_MARK))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            children.add(channel);
                            ChannelPipeline pipeline = channel.pipeline();
                            pipeline.addLast("httpCodec", new HttpServerCodec());
                            pipeline.addLast("httpAggregator", new HttpObjectAggregator(properties.getMaxFrameBytes()));
                            pipeline.addLast("originGate", new OriginGate(properties));
                            pipeline.addLast("websocketProtocol", new WebSocketServerProtocolHandler(
                                    properties.getPath(), null, true, properties.getMaxFrameBytes()));
                            pipeline.addLast("websocketAggregator", new WebSocketFrameAggregator(properties.getMaxFrameBytes()));
                            pipeline.addLast("readerIdle", new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS));
                            pipeline.addLast("frameHandler", new FrameHandler(hub));
                        }
                    });
            ChannelFuture bound = bootstrap.bind(properties.getHost(), properties.getPort()).syncUninterruptibly();
            if (!bound.isSuccess()) {
                throw new IllegalStateException("Could not bind realtime server", bound.cause());
            }
            serverChannel = bound.channel();
            running = true;
            log.info("Realtime WebSocket server listening on {}:{}{}", properties.getHost(), properties.getPort(), properties.getPath());
        } catch (RuntimeException exception) {
            shutdownGroups();
            throw exception;
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        children.close().awaitUninterruptibly();
        Channel server = serverChannel;
        serverChannel = null;
        if (server != null) server.close().awaitUninterruptibly();
        shutdownGroups();
    }

    private void shutdownGroups() {
        EventLoopGroup workerGroup = workers;
        EventLoopGroup bossGroup = boss;
        workers = null;
        boss = null;
        if (workerGroup != null) workerGroup.shutdownGracefully().awaitUninterruptibly();
        if (bossGroup != null) bossGroup.shutdownGracefully().awaitUninterruptibly();
    }

    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }

    private static final class OriginGate extends ChannelInboundHandlerAdapter {
        private final SocketProperties properties;

        private OriginGate(SocketProperties properties) { this.properties = properties; }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (!(message instanceof FullHttpRequest request)) {
                ctx.fireChannelRead(message);
                return;
            }
            String origin = request.headers().get("Origin");
            String cookie = request.headers().get("Cookie");
            boolean allowed = origin == null || properties.getAllowedOrigins().contains(origin);
            if (!allowed) {
                request.release();
                DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN);
                response.headers().setInt("Content-Length", 0);
                ctx.writeAndFlush(response).addListener(f -> ctx.close());
                return;
            }
            ctx.channel().attr(COOKIE).set(cookie);
            ctx.fireChannelRead(message);
        }
    }

    private static final class FrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        private final NativeHub hub;
        private SocketSession session;

        private FrameHandler(NativeHub hub) { this.hub = hub; }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
            if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                session = hub.open(ctx.channel(), ctx.channel().attr(COOKIE).get());
                ctx.writeAndFlush(new TextWebSocketFrame("{\"op\":\"CONNECTED\"}"));
                return;
            }
            if (event instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
                ctx.close();
                return;
            }
            super.userEventTriggered(ctx, event);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame message) {
            if (message instanceof TextWebSocketFrame text) {
                hub.handle(session, text.text());
            } else if (message instanceof BinaryWebSocketFrame) {
                ctx.close();
            } else {
                ctx.close();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            hub.close(session);
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            hub.close(session);
            ctx.close();
        }
    }
}
