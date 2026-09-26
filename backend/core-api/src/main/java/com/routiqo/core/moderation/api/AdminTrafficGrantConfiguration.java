package com.routiqo.core.moderation.api;

import com.routiqo.core.security.ConditionalOnExactlyTrue;
import com.routiqo.core.moderation.infrastructure.JdbcTrafficGrantAdmin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("web-auth & persistence & google-auth")
@ConditionalOnExactlyTrue({"ROUTIQO_ADMIN_ENABLED", "ROUTIQO_V3_ADMIN_ENABLED", "ROUTIQO_V3_GRANT_ADMIN_ENABLED"})
public class AdminTrafficGrantConfiguration {
    @Bean JdbcTrafficGrantAdmin jdbcTrafficGrantAdmin(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcTrafficGrantAdmin(jdbc, manager);
    }
}
