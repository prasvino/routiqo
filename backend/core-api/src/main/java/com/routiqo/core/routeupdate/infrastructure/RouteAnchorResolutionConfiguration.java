package com.routiqo.core.routeupdate.infrastructure;

import com.routiqo.core.routeupdate.application.RouteAnchorResolver;
import com.routiqo.core.routeupdate.application.RouteBindingAttemptParticipant;
import com.routiqo.core.routeupdate.application.RouteBindingService;
import com.routiqo.core.routeupdate.application.LiveRouteContextParticipant;
import com.routiqo.core.routeupdate.application.CatalogSignalService;
import com.routiqo.core.routeupdate.application.SignalStorageService;
import com.routiqo.core.routeupdate.application.PrivateAnchorChoiceService;
import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.privacy.application.PresenceConsentParticipant;
import com.routiqo.core.moderation.application.ContributionRestrictionReader;
import com.routiqo.core.routing.application.RouteProvider;
import com.routiqo.core.routing.domain.RoutingRegion;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile({"web-auth & routing", "native-auth & routing"})
@ConditionalOnProperty(name = "ROUTIQO_LIVE_ANCHOR_RESOLVER_ENABLED", havingValue = "true")
public class RouteAnchorResolutionConfiguration {
    private static final String CONFIGURATION_ERROR = "Route anchor resolver is not configured";

    @Bean RouteAnchorCatalog routeAnchorCatalog(Environment environment, RoutingRegion region) {
        try {
            String configured = environment.getProperty("ROUTIQO_LIVE_ANCHOR_CATALOG_PATH");
            if (configured == null || configured.isBlank() || configured.length() > 4096) throw invalid();
            RouteAnchorCatalog catalog = new RouteAnchorCatalogLoader().load(Path.of(configured));
            if (catalog.anchors().stream().anyMatch(anchor -> !region.contains(anchor.location()))) {
                throw invalid();
            }
            return catalog;
        } catch (RuntimeException invalidConfiguration) {
            throw invalid();
        }
    }

    @Bean RouteAnchorResolver routeAnchorResolver(RouteProvider routes, RouteAnchorCatalog catalog) {
        try {
            return new RouteAnchorResolver(routes, catalog);
        } catch (RuntimeException invalidConfiguration) {
            throw invalid();
        }
    }

    @Bean
    @Profile("persistence")
    RouteBindingService routeBindingService(JourneyWriteAuthority journeys,
            PresenceConsentParticipant consents, LiveRouteContextParticipant contexts,
            RouteBindingAttemptParticipant attempts, RouteAnchorResolver resolver,
            AuthRateGate rates) {
        return new RouteBindingService(journeys, consents, contexts, attempts, resolver, rates);
    }

    @Bean
    @Profile("persistence")
    CatalogSignalService catalogSignalService(
            SignalStorageService storage, RouteAnchorCatalog catalog) {
        return new CatalogSignalService(storage, catalog);
    }

    @Bean
    @Profile("persistence")
    @ConditionalOnProperty(name = {"ROUTIQO_LIVE_SIGNAL_API_ENABLED",
            "ROUTIQO_LIVE_CHOICE_API_ENABLED"}, havingValue = "true")
    PrivateAnchorChoiceService privateAnchorChoiceService(JourneyWriteAuthority journeys,
            PresenceConsentParticipant consents, LiveRouteContextParticipant contexts,
            ContributionRestrictionReader restrictions, RouteAnchorCatalog catalog) {
        return new PrivateAnchorChoiceService(
                journeys, consents, contexts, restrictions, catalog, Clock.systemUTC());
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException(CONFIGURATION_ERROR);
    }
}
