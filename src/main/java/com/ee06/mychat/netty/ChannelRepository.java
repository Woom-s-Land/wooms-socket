package com.ee06.mychat.netty;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ChannelRepository {
    private ConcurrentMap<String, ChannelGroup> channelCache = new ConcurrentHashMap<>();

    public void put(String key, ChannelGroup value) {
        channelCache.put(key, value);
    }

    public ChannelGroup get(String key) {
        return channelCache.getOrDefault(key, new DefaultChannelGroup(GlobalEventExecutor.INSTANCE));
    }

    public void remove(String key) { this.channelCache.remove(key); }

    public int size() {
        return this.channelCache.size();
    }
}
