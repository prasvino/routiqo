package com.routiqo.core.moderation.infrastructure;

import com.routiqo.core.moderation.application.ContributionRestrictionAuditCleanup;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@Profile("persistence")
@ConditionalOnProperty(name = "routiqo.moderation.expiry-maintenance-enabled",
        havingValue = "true")
@EnableScheduling
public class ContributionRestrictionExpiryMaintenanceConfiguration {
    @Bean
    ThreadPoolTaskScheduler moderationExpiryTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("moderation-expiry-");
        return scheduler;
    }

    @Bean
    ContributionRestrictionExpiryMaintenanceJob contributionRestrictionExpiryMaintenanceJob(
            ContributionRestrictionAuditCleanup cleanup) {
        return new ContributionRestrictionExpiryMaintenanceJob(cleanup);
    }
}
