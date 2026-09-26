package com.routiqo.core.moderation.infrastructure;

import com.routiqo.core.identity.application.EnabledAccountPairAuthority;
import com.routiqo.core.moderation.application.AuditedContributionRestrictionParticipant;
import com.routiqo.core.moderation.application.AuditedContributionRestrictionService;
import com.routiqo.core.moderation.application.ContributionRestrictionAuditCleanup;
import com.routiqo.core.moderation.application.DirectionalBlockParticipant;
import com.routiqo.core.moderation.application.DurableBlockPolicyService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("persistence")
public class ModerationPersistenceConfiguration {
    @Bean
    JdbcContributionRestrictionParticipant contributionRestrictionParticipant(JdbcTemplate jdbc) {
        return new JdbcContributionRestrictionParticipant(jdbc);
    }

    @Bean
    AuditedContributionRestrictionParticipant auditedContributionRestrictionParticipant(
            JdbcTemplate jdbc, JdbcContributionRestrictionParticipant restrictions) {
        return new JdbcAuditedContributionRestrictionParticipant(jdbc, restrictions);
    }

    @Bean
    AuditedContributionRestrictionService auditedContributionRestrictionService(
            EnabledAccountPairAuthority accounts,
            AuditedContributionRestrictionParticipant participant) {
        return new AuditedContributionRestrictionService(accounts, participant, Clock.systemUTC());
    }

    @Bean
    ContributionRestrictionAuditCleanup contributionRestrictionAuditCleanup(
            JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcContributionRestrictionAuditCleanup(jdbc, manager, Clock.systemUTC());
    }

    @Bean
    JdbcDirectionalBlockParticipant directionalBlockParticipant(JdbcTemplate jdbc) {
        return new JdbcDirectionalBlockParticipant(jdbc);
    }

    @Bean
    DurableBlockPolicyService durableBlockPolicyService(
            EnabledAccountPairAuthority accounts, DirectionalBlockParticipant edges) {
        return new DurableBlockPolicyService(accounts, edges);
    }
}
