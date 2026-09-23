package com.routiqo.core.publiclive.infrastructure;

import com.routiqo.core.identity.application.AccountWriteAuthority;
import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.moderation.application.ContributionRestrictionReader;
import com.routiqo.core.privacy.application.PresenceConsentParticipant;
import com.routiqo.core.publiclive.application.PublicSignalIntentService;
import com.routiqo.core.publiclive.application.PublicSignalIntentStore;
import com.routiqo.core.routeupdate.application.LiveRouteContextParticipant;
import com.routiqo.core.routeupdate.application.SignalStorageStore;
import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import com.routiqo.core.verification.application.VerifiedContributorReader;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Internal purpose-change boundary only; no publication transport exists. */
@Configuration(proxyBeanMethods = false)
@Profile("web-auth & routing & persistence")
@ConditionalOnProperty(name = "ROUTIQO_LIVE_ANCHOR_RESOLVER_ENABLED", havingValue = "true")
public class PublicSignalIntentConfiguration {
    @Bean PublicSignalIntentStore publicSignalIntentStore(JdbcTemplate jdbc) {
        return new JdbcPublicSignalIntentStore(jdbc);
    }

    @Bean PublicSignalIntentService publicSignalIntentService(JourneyWriteAuthority journeys,
            AccountWriteAuthority accounts, PresenceConsentParticipant consents,
            LiveRouteContextParticipant contexts, ContributionRestrictionReader restrictions,
            VerifiedContributorReader verification, RouteAnchorCatalog catalog,
            PublicSignalIntentStore store, SignalStorageStore receipts) {
        return new PublicSignalIntentService(journeys, accounts, consents, contexts,
                restrictions, verification, catalog, store, receipts, Clock.systemUTC());
    }

    @Bean JdbcPublicSignalIntentCleanup publicSignalIntentCleanup(JdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        return new JdbcPublicSignalIntentCleanup(jdbc, manager, Clock.systemUTC());
    }
}
