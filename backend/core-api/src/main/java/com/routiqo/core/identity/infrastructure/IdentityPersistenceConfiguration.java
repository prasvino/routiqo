package com.routiqo.core.identity.infrastructure;

import com.routiqo.core.identity.application.GoogleAccountStore;
import com.routiqo.core.identity.application.AccountAgeReader;
import com.routiqo.core.identity.application.AccountWriteAuthority;
import com.routiqo.core.identity.application.EnabledAccountPairAuthority;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import com.routiqo.core.identity.application.SessionStore;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("persistence")
public class IdentityPersistenceConfiguration {
    @Bean AccountWriteAuthority accountWriteAuthority(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcAccountWriteAuthority(jdbc, manager);
    }
    @Bean EnabledAccountPairAuthority enabledAccountPairAuthority(
            JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcEnabledAccountPairAuthority(jdbc, manager);
    }
    @Bean SessionStore sessionStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcSessionStore(jdbc, manager);
    }
    @Bean AccountAgeReader accountAgeReader(JdbcTemplate jdbc) { return new JdbcAccountAgeReader(jdbc); }
    @Bean GoogleAccountStore googleAccountStore(JdbcTemplate jdbc) { return new JdbcGoogleAccountStore(jdbc); }
}
