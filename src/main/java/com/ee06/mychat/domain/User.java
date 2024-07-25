package com.ee06.mychat.domain;

import com.ee06.mychat.netty.ChannelRepository;
import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.util.AttributeKey;
import lombok.Getter;
import lombok.NonNull;

public class User {
    @Getter
    private final String username;
    private final Channel channel;
    private final String channelId;
    public static final AttributeKey<User> USER_ATTRIBUTE_KEY = AttributeKey.newInstance("USER");

    private User(String username, Channel channel, String channelId){
        this.username = username;
        this.channel = channel;
        this.channelId = channelId;
    }

    public static User of(@NonNull String userName, @NonNull Channel channel, @NonNull String channelId) {
        return new User(userName, channel, channelId);
    }
    public static User current(Channel channel) {
        User user = channel.attr(User.USER_ATTRIBUTE_KEY).get();
        if ( user == null ){
            throw new UserLoggedOutException();
        }
        return user;
    }
    public void login(ChannelRepository channelRepository, Channel channel) {
        channel.attr(USER_ATTRIBUTE_KEY).set(this);
        ChannelGroup channelGroup = channelRepository.get(channelId);
        channelGroup.add(channel);
        channelRepository.put(channelId, channelGroup);
    }
    public void logout(ChannelRepository channelRepository, Channel channel) {
        ChannelGroup channelGroup = channelRepository.get(channelId);
        channelGroup.remove(channel);
        channelRepository.put(channelId, channelGroup);
        // 종료 로직 있어야할듯
    }
    public void broadcast(ChannelRepository channelRepository, Object message) {
        ChannelGroup channelGroup = channelRepository.get(channelId);
        channelGroup.write(this.username);
        channelGroup.write(">");
        channelGroup.writeAndFlush(message + "\n\r");
    }
}
