package com.routiqo.core.spot.infrastructure;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.journey.application.ActiveJourneyReader;
import com.routiqo.core.moderation.application.BlockedAccountsReader;
import com.routiqo.core.routing.domain.RoutingRegion;
import com.routiqo.core.security.ConditionalOnExactlyTrue;
import com.routiqo.core.spot.application.SpotActivityReader;
import com.routiqo.core.spot.application.SpotActivityService;
import com.routiqo.core.spot.application.SpotCatalogService;
import com.routiqo.core.spot.domain.SpotCatalog;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Loads the curated catalog at startup only when {@code ROUTIQO_SPOTS_API_ENABLED} is exactly
 * {@code true}. With the flag on, a missing, invalid or out-of-region catalog stops startup.
 */
@Configuration(proxyBeanMethods = false)
@Profile("native-auth & routing & persistence")
@ConditionalOnExactlyTrue("ROUTIQO_SPOTS_API_ENABLED")
public class SpotsConfiguration {
    private static final String CONFIGURATION_ERROR = "Spot catalog is not configured";

    @Bean SpotCatalog spotCatalog(Environment environment, RoutingRegion region) {
        try {
            String configured = environment.getProperty("ROUTIQO_SPOT_CATALOG_PATH");
            if (configured == null || configured.isBlank() || configured.length() > 4096) throw invalid();
            SpotCatalog catalog = new SpotCatalogLoader().load(Path.of(configured));
            if (catalog.spots().stream().anyMatch(spot -> !region.contains(spot.location()))) throw invalid();
            return catalog;
        } catch (RuntimeException invalidConfiguration) {
            throw invalid();
        }
    }

    @Bean SpotCatalogService spotCatalogService(AuthRateGate rates) {
        return new SpotCatalogService(rates);
    }

    @Bean SpotActivityReader spotActivityReader(JdbcTemplate jdbc) {
        return new JdbcSpotActivityReader(jdbc);
    }

    @Bean SpotActivityService spotActivityService(AuthRateGate rates, ActiveJourneyReader journeys,
            SpotCatalog catalog, SpotActivityReader content, BlockedAccountsReader blocks) {
        return new SpotActivityService(rates, journeys, catalog, Clock.systemUTC(), content, blocks);
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException(CONFIGURATION_ERROR);
    }
}
