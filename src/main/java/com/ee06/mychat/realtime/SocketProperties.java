package com.ee06.mychat.realtime;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties("wooms.socket")
public class SocketProperties {
    private String host = "127.0.0.1";
    private int port = 8090;
    private String path = "/ws";
    private String backendUrl = "http://localhost:8080";
    private List<String> allowedOrigins = List.of("http://localhost:5173", "http://localhost:3000");
    private int maxFrameBytes = 65536;
    private int roomThreads = 4;
    private Duration backendTimeout = Duration.ofSeconds(5);
    private String storageSecret = "";
}
