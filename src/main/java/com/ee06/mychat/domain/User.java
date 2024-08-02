package com.ee06.mychat.domain;

import com.ee06.mychat.netty.ChannelRepository;
import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.util.AttributeKey;
import lombok.Getter;
import lombok.NonNull;

import java.util.Objects;

public class User {
    @Getter
    private String username;
    private Channel channel;
    private String channelId;
    private String costume;
    private String mapX, mapY, charX, charY;
    private String dir;
    public static final AttributeKey<User> USER_ATTRIBUTE_KEY = AttributeKey.newInstance("USER");

    private User(String username, Channel channel, String channelId, String costume){
        this.username = username;
        this.channel = channel;
        this.channelId = channelId;
        this.costume = costume;
        this.mapX = "100";
        this.mapY = "100";
        this.charX = "100";
        this.charY = "100";
        dir = "s";
    }

    public static User of(@NonNull String userName, @NonNull Channel channel, @NonNull String channelId, @NonNull String costume) {
        return new User(userName, channel, channelId, costume);
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
        this.channel = channel;
        channelRepository.put(channelId, channelGroup);
    }
    public void logout(ChannelRepository channelRepository) {
        ChannelGroup channelGroup = channelRepository.get(channelId);
        channelGroup.remove(this.channel);
        channelRepository.put(channelId, channelGroup);
        // 종료 로직 있어야할듯
    }

    public void notice(Channel channel){
        if(Objects.equals(channel, this.channel)) return ;
        channel.write("pos> ");
        channel.write(this.username + " " + costume + " " + mapX + " " + mapY + " " + charX + " " + charY + " " + dir);
        channel.writeAndFlush("\n\r");
    }

    public void join(ChannelRepository channelRepository){
        ChannelGroup channelGroup = channelRepository.get(channelId);

        channelGroup.forEach(channel -> User.current(channel).notice(this.channel));

        posBroadcast(channelRepository, this.username + " " + costume + " " + mapX + " " + mapY + " " + charX + " " + charY + " " + dir );
    }

    public void posBroadcast(ChannelRepository channelRepository, String message) {
        ChannelGroup channelGroup = channelRepository.get(channelId);
        channelGroup.stream().filter(channel1 -> !Objects.equals(channel, channel1)).forEach(channel -> {
            channel.write("pos> ");
            channel.write(this.username + " " + costume + " " + mapX + " " + mapY + " " + charX + " " + charY + " " + dir);
            channel.writeAndFlush("\n\r");
        });
    }

    public void chatBroadcast(ChannelRepository channelRepository, String message) {
        ChannelGroup channelGroup = channelRepository.get(channelId);
        channelGroup.write("chat> ");
        channelGroup.write(this.username);
        channelGroup.write(" : ");
        channelGroup.writeAndFlush(message + "\n\r");
    }
}
