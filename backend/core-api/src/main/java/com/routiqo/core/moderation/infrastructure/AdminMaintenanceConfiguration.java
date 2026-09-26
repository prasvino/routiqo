package com.routiqo.core.moderation.infrastructure;

import com.routiqo.core.security.ConditionalOnExactlyTrue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

/** Admin sign-in and Spots grant retention runs whenever the admin base is on (ADR 0075). */
@Configuration(proxyBeanMethods = false)
@Profile("persistence")
@ConditionalOnExactlyTrue({"ROUTIQO_ADMIN_ENABLED"})
@EnableScheduling
public class AdminMaintenanceConfiguration {
    @Bean JdbcAdminCleanup adminCleanup(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcAdminCleanup(jdbc, manager);
    }
    @Bean AdminMaintenanceJob adminMaintenanceJob(JdbcAdminCleanup cleanup) {
        return new AdminMaintenanceJob(cleanup);
    }

    public static final class AdminMaintenanceJob {
        private static final Logger LOG = LoggerFactory.getLogger(AdminMaintenanceJob.class);
        private final JdbcAdminCleanup cleanup;
        private final AtomicBoolean running = new AtomicBoolean();
        AdminMaintenanceJob(JdbcAdminCleanup cleanup) { this.cleanup = cleanup; }
        @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
        public void runOnce() {
            if (!running.compareAndSet(false, true)) return;
            try { cleanup.cleanup(100); }
            catch (RuntimeException unavailable) { LOG.warn("Admin retention maintenance unavailable"); }
            finally { running.set(false); }
        }
    }
}
