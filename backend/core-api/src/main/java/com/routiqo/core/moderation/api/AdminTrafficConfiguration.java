package com.routiqo.core.moderation.api;

import com.routiqo.core.moderation.infrastructure.JdbcTrafficReview;
import com.routiqo.core.security.ConditionalOnExactlyTrue;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Archived V3 traffic review: needs the admin base and its own V3 flag. */
@Configuration(proxyBeanMethods = false)
@Profile("web-auth & persistence & google-auth")
@ConditionalOnExactlyTrue({"ROUTIQO_ADMIN_ENABLED", "ROUTIQO_V3_ADMIN_ENABLED"})
public class AdminTrafficConfiguration {
    @Bean JdbcTrafficReview jdbcTrafficReview(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcTrafficReview(jdbc, manager, Clock.systemUTC());
    }
}
