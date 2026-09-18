package com.routiqo.core.routeupdate.infrastructure;

import com.routiqo.core.identity.infrastructure.AuthInfrastructureConfiguration;
import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.moderation.application.ContributionRestrictionParticipant;
import com.routiqo.core.privacy.application.PresenceConsentParticipant;
import com.routiqo.core.routeupdate.application.LiveRouteContextExpiryMaintenance;
import com.routiqo.core.routeupdate.application.SignalStorageExpiryMaintenance;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LiveExpiryMaintenanceConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RouteUpdatePersistenceConfiguration.class,
                    LiveExpiryMaintenanceConfiguration.class, TestDependencies.class)
            .withInitializer(context -> {
                try {
                    var source = new YamlPropertySourceLoader().load("persistence-test",
                            new ClassPathResource("application-persistence.yml")).getFirst();
                    context.getEnvironment().getPropertySources().addLast(source);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });

    @Test
    void signalStorageRequiresAnExplicitRestrictionParticipant() {
        new ApplicationContextRunner()
                .withUserConfiguration(RouteUpdatePersistenceConfiguration.class,
                        MissingRestrictionDependencies.class)
                .withPropertyValues("spring.profiles.active=persistence")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("ContributionRestrictionParticipant");
                });
    }

    @Test
    void requiresPersistenceAndAnExplicitEnabledFlag() {
        for (String profile : List.of("preview", "web-auth")) {
            runner.withPropertyValues("spring.profiles.active=" + profile,
                    "ROUTIQO_LIVE_EXPIRY_MAINTENANCE_ENABLED=true")
                    .run(context -> assertThat(context).hasNotFailed()
                            .doesNotHaveBean(LiveExpiryMaintenanceJob.class)
                            .doesNotHaveBean("liveExpiryTaskScheduler")
                            .doesNotHaveBean("taskScheduler"));
        }
        runner.withPropertyValues("spring.profiles.active=persistence")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(LiveExpiryMaintenanceJob.class)
                        .doesNotHaveBean("liveExpiryTaskScheduler")
                        .doesNotHaveBean("taskScheduler"));
        runner.withPropertyValues("spring.profiles.active=persistence",
                "ROUTIQO_LIVE_EXPIRY_MAINTENANCE_ENABLED=false")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(LiveExpiryMaintenanceJob.class)
                        .doesNotHaveBean("liveExpiryTaskScheduler")
                        .doesNotHaveBean("taskScheduler"));
    }

    @Test
    void enabledConfigurationComposesTheRealAdaptersAndFixedDedicatedSchedule()
            throws Exception {
        runner.withPropertyValues("spring.profiles.active=persistence",
                "ROUTIQO_LIVE_EXPIRY_MAINTENANCE_ENABLED=true")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(LiveExpiryMaintenanceJob.class)
                            .hasSingleBean(LiveRouteContextExpiryMaintenance.class)
                            .hasSingleBean(SignalStorageExpiryMaintenance.class);
                    assertThat(context.getBean(LiveRouteContextExpiryMaintenance.class))
                            .isInstanceOf(JdbcLiveRouteContextExpiryMaintenance.class);
                    assertThat(context.getBean(SignalStorageExpiryMaintenance.class))
                            .isInstanceOf(JdbcSignalStorageExpiryMaintenance.class);
                    assertThat(context.getBean("liveExpiryTaskScheduler"))
                            .isInstanceOf(ThreadPoolTaskScheduler.class);
                    assertThat(context).doesNotHaveBean("taskScheduler");
                });

        Scheduled schedule = LiveExpiryMaintenanceJob.class.getMethod("runOnce")
                .getAnnotation(Scheduled.class);
        assertThat(schedule.fixedDelay()).isEqualTo(60_000);
        assertThat(schedule.initialDelay()).isEqualTo(60_000);
        assertThat(schedule.scheduler()).isEqualTo("liveExpiryTaskScheduler");
    }

    @Test
    void liveAndAuthMaintenanceUseSeparateSchedulers() {
        runner.withUserConfiguration(AuthInfrastructureConfiguration.class)
                .withUserConfiguration(SchedulerProbeConfiguration.class)
                .withPropertyValues("spring.profiles.active=persistence,web-auth",
                        "ROUTIQO_LIVE_EXPIRY_MAINTENANCE_ENABLED=true",
                        "ROUTIQO_AUTH_RATE_SECRET=12345678901234567890123456789012")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(LiveExpiryMaintenanceJob.class)
                            .hasBean("liveExpiryTaskScheduler")
                            .hasBean("taskScheduler");
                    assertThat(context.getBean("taskScheduler"))
                            .isNotSameAs(context.getBean("liveExpiryTaskScheduler"));
                    assertThat(context.getBean("taskScheduler"))
                            .isInstanceOf(ThreadPoolTaskScheduler.class);
                    SchedulerProbe probe = context.getBean(SchedulerProbe.class);
                    assertThat(probe.await()).isTrue();
                    assertThat(probe.defaultThread.get()).startsWith("auth-maintenance-");
                    assertThat(probe.liveThread.get()).startsWith("live-expiry-");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {
        @Bean JdbcTemplate jdbcTemplate() { return new JdbcTemplate(mock(DataSource.class)); }
        @Bean PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }
        @Bean JourneyWriteAuthority journeyWriteAuthority() {
            return mock(JourneyWriteAuthority.class);
        }
        @Bean PresenceConsentParticipant presenceConsentParticipant() {
            return mock(PresenceConsentParticipant.class);
        }
        @Bean ContributionRestrictionParticipant contributionRestrictionParticipant() {
            return mock(ContributionRestrictionParticipant.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MissingRestrictionDependencies {
        @Bean JdbcTemplate jdbcTemplate() { return new JdbcTemplate(mock(DataSource.class)); }
        @Bean PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }
        @Bean JourneyWriteAuthority journeyWriteAuthority() {
            return mock(JourneyWriteAuthority.class);
        }
        @Bean PresenceConsentParticipant presenceConsentParticipant() {
            return mock(PresenceConsentParticipant.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SchedulerProbeConfiguration {
        @Bean SchedulerProbe schedulerProbe() { return new SchedulerProbe(); }
    }

    static class SchedulerProbe {
        private final CountDownLatch defaultRan = new CountDownLatch(1);
        private final CountDownLatch liveRan = new CountDownLatch(1);
        private final AtomicReference<String> defaultThread = new AtomicReference<>();
        private final AtomicReference<String> liveThread = new AtomicReference<>();

        @Scheduled(fixedDelay = 50, initialDelay = 10)
        public void runOnDefaultScheduler() {
            defaultThread.set(Thread.currentThread().getName());
            defaultRan.countDown();
        }

        @Scheduled(fixedDelay = 50, initialDelay = 10,
                scheduler = "liveExpiryTaskScheduler")
        public void runOnLiveScheduler() {
            liveThread.set(Thread.currentThread().getName());
            liveRan.countDown();
        }

        boolean await() {
            try {
                return defaultRan.await(5, TimeUnit.SECONDS)
                        && liveRan.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
