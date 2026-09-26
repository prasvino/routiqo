package com.routiqo.core.spot.infrastructure;

import com.routiqo.core.security.ConditionalOnExactlyTrue;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

/** Spot contribution expiry on its own exact flag, `ROUTIQO_SPOTS_MAINTENANCE_ENABLED` (ADR 0071). */
@Configuration(proxyBeanMethods = false)
@Profile("persistence")
@ConditionalOnExactlyTrue("ROUTIQO_SPOTS_MAINTENANCE_ENABLED")
@EnableScheduling
public class SpotMaintenanceConfiguration {
    @Bean ThreadPoolTaskScheduler spotMaintenanceScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("spot-maintenance-");
        return scheduler;
    }

    @Bean JdbcSpotContributionMaintenance spotContributionMaintenance(JdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        return new JdbcSpotContributionMaintenance(jdbc, manager, Clock.systemUTC());
    }

    @Bean Job spotMaintenanceJob(JdbcSpotContributionMaintenance maintenance) {
        return new Job(maintenance);
    }

    /** One bounded pass a minute; highlights are promoted before items are purged. */
    public static final class Job {
        private static final Logger LOG = LoggerFactory.getLogger(Job.class);
        private static final int BATCH_SIZE = 100;
        private final JdbcSpotContributionMaintenance maintenance;
        private final AtomicBoolean running = new AtomicBoolean();

        Job(JdbcSpotContributionMaintenance maintenance) { this.maintenance = maintenance; }

        @Scheduled(fixedDelay = 60_000, initialDelay = 60_000, scheduler = "spotMaintenanceScheduler")
        public void runOnce() {
            if (!running.compareAndSet(false, true)) return;
            try {
                try { maintenance.promoteHighlights(BATCH_SIZE); }
                catch (Exception failure) { LOG.warn("Spot maintenance failed: highlights"); }
                try { maintenance.purgeItems(BATCH_SIZE); }
                catch (Exception failure) { LOG.warn("Spot maintenance failed: items"); }
                try { maintenance.purgeBookkeeping(BATCH_SIZE); }
                catch (Exception failure) { LOG.warn("Spot maintenance failed: bookkeeping"); }
            } finally {
                running.set(false);
            }
        }
    }
}
