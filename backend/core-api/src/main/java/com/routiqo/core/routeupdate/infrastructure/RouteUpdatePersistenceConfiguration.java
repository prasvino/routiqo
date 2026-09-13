package com.routiqo.core.routeupdate.infrastructure;

import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.routeupdate.application.LiveRouteContextExpiryMaintenance;
import com.routiqo.core.routeupdate.application.LiveRouteContextParticipant;
import com.routiqo.core.routeupdate.application.LiveRouteContextService;
import com.routiqo.core.routeupdate.application.SignalStorageExpiryMaintenance;
import com.routiqo.core.routeupdate.application.SignalStorageService;
import com.routiqo.core.routeupdate.application.SignalStorageStore;
import com.routiqo.core.privacy.application.PresenceConsentParticipant;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("persistence")
public class RouteUpdatePersistenceConfiguration {
    @Bean
    JdbcLiveRouteContextParticipant liveRouteContextParticipant(JdbcTemplate jdbc) {
        return new JdbcLiveRouteContextParticipant(jdbc, Clock.systemUTC());
    }

    @Bean
    LiveRouteContextService liveRouteContextService(
            JourneyWriteAuthority journeys, LiveRouteContextParticipant contexts) {
        return new LiveRouteContextService(journeys, contexts);
    }

    @Bean
    LiveRouteContextExpiryMaintenance liveRouteContextExpiryMaintenance(
            JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcLiveRouteContextExpiryMaintenance(jdbc, manager, Clock.systemUTC());
    }

    @Bean
    JdbcSignalStorageStore signalStorageStore(JdbcTemplate jdbc) {
        return new JdbcSignalStorageStore(jdbc);
    }

    @Bean
    SignalStorageService signalStorageService(JourneyWriteAuthority journeys,
            PresenceConsentParticipant consents, LiveRouteContextParticipant contexts,
            SignalStorageStore store) {
        return new SignalStorageService(journeys, consents, contexts, store, Clock.systemUTC());
    }

    @Bean
    SignalStorageExpiryMaintenance signalStorageExpiryMaintenance(
            JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcSignalStorageExpiryMaintenance(jdbc, manager, Clock.systemUTC());
    }
}
