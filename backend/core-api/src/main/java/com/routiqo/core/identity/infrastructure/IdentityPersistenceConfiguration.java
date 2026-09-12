package com.routiqo.core.identity.infrastructure;

import com.routiqo.core.identity.application.GoogleAccountStore;
import com.routiqo.core.identity.application.AccountWriteAuthority;
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
    @Bean SessionStore sessionStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcSessionStore(jdbc, manager);
    }
    @Bean GoogleAccountStore googleAccountStore(JdbcTemplate jdbc) { return new JdbcGoogleAccountStore(jdbc); }
}
