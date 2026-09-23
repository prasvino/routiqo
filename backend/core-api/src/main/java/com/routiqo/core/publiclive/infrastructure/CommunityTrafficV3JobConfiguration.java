package com.routiqo.core.publiclive.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@Profile("web-auth & routing & persistence")
@ConditionalOnProperty(name = {"ROUTIQO_COMMUNITY_TRAFFIC_V3_ENABLED",
        "ROUTIQO_COMMUNITY_TRAFFIC_V3_PUBLISHER_ENABLED"}, havingValue = "true")
@EnableScheduling
public class CommunityTrafficV3JobConfiguration {
    @Bean ThreadPoolTaskScheduler communityTrafficV3TaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("community-traffic-v3-");
        return scheduler;
    }

    @Bean CommunityTrafficV3Job communityTrafficV3Job(JdbcCommunityTrafficPublisherV3 publisher) {
        return new CommunityTrafficV3Job(publisher);
    }
}
