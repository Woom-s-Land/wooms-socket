package com.ee06.mychat.realtime.mafia.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Scheduler for mafia phase timers. A timer only re-enqueues its callback on
 * the room's RoomQueue; game state is never touched on this thread.
 */
@Configuration
public class MafiaSchedulerConfig {

    @Bean
    public ThreadPoolTaskScheduler mafiaTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("mafia-timer-");
        scheduler.initialize();
        return scheduler;
    }
}
