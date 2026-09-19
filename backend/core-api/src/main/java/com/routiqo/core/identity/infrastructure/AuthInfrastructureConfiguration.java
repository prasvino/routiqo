package com.routiqo.core.identity.infrastructure;

import com.routiqo.core.identity.application.AuthRateGate;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@Profile("persistence & (web-auth | native-auth)")
@EnableScheduling
public class AuthInfrastructureConfiguration {
    @Bean("taskScheduler")
    ThreadPoolTaskScheduler authMaintenanceTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("auth-maintenance-");
        return scheduler;
    }

    @Bean Clock authRateClock() { return Clock.systemUTC(); }

    @Bean AuthRateGate authRateGate(JdbcTemplate jdbc,
            @Value("${ROUTIQO_AUTH_RATE_SECRET}") String secret, Clock clock) {
        return new JdbcAuthRateGate(jdbc, secret, clock);
    }

    @Bean AuthMaintenance authMaintenance(JdbcTemplate jdbc) { return new AuthMaintenance(jdbc); }
}
