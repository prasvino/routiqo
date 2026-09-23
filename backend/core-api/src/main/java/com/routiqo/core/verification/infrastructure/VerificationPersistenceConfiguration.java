package com.routiqo.core.verification.infrastructure;

import com.routiqo.core.identity.application.EnabledAccountPairAuthority;
import com.routiqo.core.verification.application.VerificationParticipant;
import com.routiqo.core.verification.application.VerifiedContributorService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("persistence")
public class VerificationPersistenceConfiguration {
    @Bean
    JdbcVerifiedContributorAuthority jdbcVerifiedContributorAuthority(JdbcTemplate jdbc) {
        return new JdbcVerifiedContributorAuthority(jdbc);
    }

    @Bean
    VerifiedContributorService verifiedContributorService(EnabledAccountPairAuthority accounts,
            VerificationParticipant participant) {
        return new VerifiedContributorService(accounts, participant, Clock.systemUTC());
    }

    @Bean
    VerificationAuditCleanup verificationAuditCleanup(JdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        return new VerificationAuditCleanup(jdbc, manager, Clock.systemUTC());
    }
}
