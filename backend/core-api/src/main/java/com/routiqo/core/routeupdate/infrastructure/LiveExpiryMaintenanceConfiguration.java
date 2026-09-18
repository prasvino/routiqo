package com.routiqo.core.routeupdate.infrastructure;

import com.routiqo.core.routeupdate.application.LiveRouteContextExpiryMaintenance;
import com.routiqo.core.routeupdate.application.SignalStorageExpiryMaintenance;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@Profile("persistence")
@ConditionalOnProperty(name = "routiqo.live.expiry-maintenance-enabled", havingValue = "true")
@EnableScheduling
public class LiveExpiryMaintenanceConfiguration {
    @Bean
    ThreadPoolTaskScheduler liveExpiryTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("live-expiry-");
        return scheduler;
    }

    // Keep unqualified auth maintenance on its own scheduler when both jobs are active.
    @Bean("taskScheduler")
    @Profile("web-auth | native-auth")
    ThreadPoolTaskScheduler defaultTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("auth-maintenance-");
        return scheduler;
    }

    @Bean
    LiveExpiryMaintenanceJob liveExpiryMaintenanceJob(
            LiveRouteContextExpiryMaintenance contexts, SignalStorageExpiryMaintenance signals) {
        return new LiveExpiryMaintenanceJob(contexts, signals);
    }
}
