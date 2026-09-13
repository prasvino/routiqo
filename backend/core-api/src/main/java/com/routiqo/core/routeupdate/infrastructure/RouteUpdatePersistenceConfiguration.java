package com.routiqo.core.routeupdate.infrastructure;

import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.routeupdate.application.LiveRouteContextExpiryMaintenance;
import com.routiqo.core.routeupdate.application.LiveRouteContextParticipant;
import com.routiqo.core.routeupdate.application.LiveRouteContextService;
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
}
