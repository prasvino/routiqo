package com.routiqo.core.publiclive.infrastructure;

import com.routiqo.core.identity.application.AccountWriteAuthority;
import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.moderation.application.ContributionRestrictionReader;
import com.routiqo.core.privacy.application.PresenceConsentParticipant;
import com.routiqo.core.publiclive.application.CommunityTrafficCandidateStore;
import com.routiqo.core.publiclive.application.CommunityTrafficShareService;
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

@Configuration(proxyBeanMethods = false)
@Profile("web-auth & routing & persistence")
@ConditionalOnProperty(name = {"ROUTIQO_COMMUNITY_TRAFFIC_V3_ENABLED",
        "ROUTIQO_LIVE_ANCHOR_RESOLVER_ENABLED"}, havingValue = "true")
public class CommunityTrafficShareConfiguration {
    @Bean CommunityTrafficCandidateStore communityTrafficCandidateStore(JdbcTemplate jdbc) {
        return new JdbcCommunityTrafficCandidateStore(jdbc);
    }

    @Bean CommunityTrafficShareService communityTrafficShareService(JourneyWriteAuthority journeys,
            AccountWriteAuthority accounts, PresenceConsentParticipant consents,
            LiveRouteContextParticipant contexts, ContributionRestrictionReader restrictions,
            VerifiedContributorReader verification, RouteAnchorCatalog catalog,
            SignalStorageStore receipts, CommunityTrafficCandidateStore store) {
        return new CommunityTrafficShareService(journeys, accounts, consents, contexts,
                restrictions, verification, catalog, receipts, store, Clock.systemUTC());
    }
}
