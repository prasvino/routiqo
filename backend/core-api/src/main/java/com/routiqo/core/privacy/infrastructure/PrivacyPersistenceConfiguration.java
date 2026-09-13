package com.routiqo.core.privacy.infrastructure;

import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.privacy.application.PresenceConsentParticipant;
import com.routiqo.core.privacy.application.PresenceConsentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("persistence")
public class PrivacyPersistenceConfiguration {
    @Bean
    JdbcPresenceConsentParticipant presenceConsentParticipant(JdbcTemplate jdbc) {
        return new JdbcPresenceConsentParticipant(jdbc);
    }

    @Bean
    PresenceConsentService presenceConsentService(
            JourneyWriteAuthority journeys, PresenceConsentParticipant consents) {
        return new PresenceConsentService(journeys, consents);
    }
}
