package com.ee06.mychat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.ee06.mychat.realtime")
public class MychatApplication {
    public static void main(String[] args) {
        SpringApplication.run(MychatApplication.class, args);
    }
}
