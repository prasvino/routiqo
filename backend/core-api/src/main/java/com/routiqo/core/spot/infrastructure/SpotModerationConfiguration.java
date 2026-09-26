package com.routiqo.core.spot.infrastructure;

import com.routiqo.core.identity.application.AccountWriteAuthority;
import com.routiqo.core.moderation.application.ModerationAccountFacts;
import com.routiqo.core.moderation.application.OperatorGrantAuthority;
import com.routiqo.core.security.ConditionalOnExactlyTrue;
import com.routiqo.core.spot.application.SpotModerationService;
import com.routiqo.core.spot.application.SpotModerationStore;
import com.routiqo.core.spot.domain.SpotCatalog;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Spots moderator queue and decisions (ADR 0075): the admin base, the Spots admin flag, and the
 * Spots API and contributions they moderate, all exactly "true".
 */
@Configuration(proxyBeanMethods = false)
@Profile("web-auth & native-auth & routing & persistence & google-auth")
@ConditionalOnExactlyTrue({"ROUTIQO_ADMIN_ENABLED", "ROUTIQO_SPOTS_ADMIN_ENABLED", "ROUTIQO_SPOTS_API_ENABLED",
    "ROUTIQO_SPOTS_CONTRIBUTIONS_ENABLED"})
@EnableScheduling
public class SpotModerationConfiguration {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SpotModerationConfiguration.class);

    @Bean SpotModerationStore spotModerationStore(JdbcTemplate jdbc) {
        return new JdbcSpotModerationStore(jdbc);
    }

    @Bean SpotModerationService spotModerationService(AccountWriteAuthority accounts, SpotModerationStore store,
            OperatorGrantAuthority grants, ModerationAccountFacts facts, SpotCatalog catalog) {
        return new SpotModerationService(accounts, store, grants, facts, catalog, Clock.systemUTC());
    }

    /** Moderation records keep their 30-day (references: 30-minute) retention whenever moderation is on. */
    @Bean SpotModerationRetention spotModerationRetention(SpotModerationStore store, PlatformTransactionManager manager) {
        return new SpotModerationRetention(store, manager);
    }

    public static final class SpotModerationRetention {
        private final SpotModerationStore store;
        private final TransactionTemplate transaction;
        SpotModerationRetention(SpotModerationStore store, PlatformTransactionManager manager) {
            this.store = store;
            this.transaction = new TransactionTemplate(manager);
            this.transaction.setTimeout(5);
        }
        @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
        public void runOnce() {
            try { transaction.execute(status -> store.purgeExpired(100, java.time.Instant.now())); }
            catch (RuntimeException unavailable) { LOG.warn("Spot moderation retention unavailable"); }
        }
    }
}
