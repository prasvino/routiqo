package com.routiqo.core.moderation.infrastructure;

import com.routiqo.core.identity.application.AccountWriteAuthority;
import com.routiqo.core.moderation.application.ContributionRestrictionParticipant;
import com.routiqo.core.moderation.application.ContributionRestrictionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("persistence")
public class ModerationPersistenceConfiguration {
    @Bean
    JdbcContributionRestrictionParticipant contributionRestrictionParticipant(JdbcTemplate jdbc) {
        return new JdbcContributionRestrictionParticipant(jdbc);
    }

    @Bean
    ContributionRestrictionService contributionRestrictionService(
            AccountWriteAuthority accounts, ContributionRestrictionParticipant participant) {
        return new ContributionRestrictionService(accounts, participant);
    }
}
