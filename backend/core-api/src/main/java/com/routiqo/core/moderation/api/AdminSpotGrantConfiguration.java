package com.routiqo.core.moderation.api;

import com.routiqo.core.moderation.infrastructure.JdbcSpotGrantAdmin;
import com.routiqo.core.security.ConditionalOnExactlyTrue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("web-auth & persistence & google-auth")
@ConditionalOnExactlyTrue({"ROUTIQO_ADMIN_ENABLED", "ROUTIQO_SPOTS_GRANT_ADMIN_ENABLED"})
public class AdminSpotGrantConfiguration {
    @Bean JdbcSpotGrantAdmin jdbcSpotGrantAdmin(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcSpotGrantAdmin(jdbc, manager);
    }
}
