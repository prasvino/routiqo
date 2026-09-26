package com.routiqo.core.spot.infrastructure;

import com.routiqo.core.identity.application.AccountWriteAuthority;
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

/**
 * The Spots moderator queue and decisions (ADR 0075): the admin base, the Spots admin flag, and the
 * Spots API and contributions they moderate, all exactly "true".
 */
@Configuration(proxyBeanMethods = false)
@Profile("web-auth & native-auth & routing & persistence & google-auth")
@ConditionalOnExactlyTrue({"ROUTIQO_ADMIN_ENABLED", "ROUTIQO_SPOTS_ADMIN_ENABLED", "ROUTIQO_SPOTS_API_ENABLED",
    "ROUTIQO_SPOTS_CONTRIBUTIONS_ENABLED"})
public class SpotModerationConfiguration {
    @Bean SpotModerationStore spotModerationStore(JdbcTemplate jdbc) {
        return new JdbcSpotModerationStore(jdbc);
    }

    @Bean SpotModerationService spotModerationService(AccountWriteAuthority accounts, SpotModerationStore store,
            OperatorGrantAuthority grants, SpotCatalog catalog) {
        return new SpotModerationService(accounts, store, grants, catalog, Clock.systemUTC());
    }
}
