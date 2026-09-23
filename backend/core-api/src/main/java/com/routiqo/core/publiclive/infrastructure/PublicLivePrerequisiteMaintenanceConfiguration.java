package com.routiqo.core.publiclive.infrastructure;

import com.routiqo.core.verification.infrastructure.VerificationAuditCleanup;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Separate default-off maintenance switch; never enables traveller publication. */
@Configuration(proxyBeanMethods = false)
@Profile("web-auth & routing & persistence")
@ConditionalOnProperty(name = {"routiqo.public-live.prerequisite-maintenance-enabled",
        "ROUTIQO_LIVE_ANCHOR_RESOLVER_ENABLED"}, havingValue = "true")
@EnableScheduling
public class PublicLivePrerequisiteMaintenanceConfiguration {
    @Bean ThreadPoolTaskScheduler publicLivePrerequisiteTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("public-live-retention-");
        return scheduler;
    }

    @Bean
    PublicLivePrerequisiteMaintenanceJob publicLivePrerequisiteMaintenanceJob(
            JdbcPublicSignalIntentCleanup intents, VerificationAuditCleanup audits) {
        return new PublicLivePrerequisiteMaintenanceJob(intents, audits);
    }
}
