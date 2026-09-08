package com.routiqo.core.identity.infrastructure;

import com.routiqo.core.identity.application.GoogleIdentityVerifier.Identity;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

class GoogleAccountStoreTest {
    @Test void firstLoginRaceResolvesOneAccountAndDisabledAccountsStayDisabled() throws Exception {
        try (var database = new PostgreSQLContainer("postgres:16-alpine")) {
            database.start();
            var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
            Flyway.configure().dataSource(source).load().migrate();
            var jdbc = new JdbcTemplate(source);
            var identity = new Identity("google", "subject-for-local-test");
            var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                Callable<java.util.UUID> login = () -> {
                    ready.countDown();
                    if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Login race did not start");
                    return new JdbcGoogleAccountStore(new JdbcTemplate(source)).resolve(identity);
                };
                var first = executor.submit(login); var second = executor.submit(login);
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); go.countDown();
                var account = first.get(20, TimeUnit.SECONDS);
                assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(account);
                assertThat(new JdbcGoogleAccountStore(jdbc).resolve(identity)).isEqualTo(account);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM routiqo_account", Long.class)).isEqualTo(1L);
                var other = new JdbcGoogleAccountStore(jdbc).resolve(new Identity("google", "another-subject"));
                assertThat(other).isNotEqualTo(account);
                jdbc.update("UPDATE routiqo_account SET enabled = FALSE WHERE id = ?", account);
                assertThatThrownBy(() -> new JdbcGoogleAccountStore(jdbc).resolve(identity)).isInstanceOf(SecurityException.class);
                assertThat(jdbc.queryForObject("SELECT enabled FROM routiqo_account WHERE id = ?", Boolean.class, account)).isFalse();
                assertThatThrownBy(() -> new JdbcGoogleAccountStore(jdbc).resolve(new Identity("untrusted", identity.subject())))
                        .isInstanceOf(SecurityException.class);
            }
        }
    }
}
