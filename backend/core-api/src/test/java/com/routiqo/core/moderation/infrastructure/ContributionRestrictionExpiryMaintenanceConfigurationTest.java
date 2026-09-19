package com.routiqo.core.moderation.infrastructure;

import com.routiqo.core.identity.infrastructure.AuthInfrastructureConfiguration;
import com.routiqo.core.moderation.application.ContributionRestrictionAuditCleanup;
import com.routiqo.core.routeupdate.application.LiveRouteContextExpiryMaintenance;
import com.routiqo.core.routeupdate.application.SignalStorageExpiryMaintenance;
import com.routiqo.core.routeupdate.infrastructure.LiveExpiryMaintenanceConfiguration;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ContributionRestrictionExpiryMaintenanceConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ContributionRestrictionExpiryMaintenanceConfiguration.class,
                    CleanupDependency.class)
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
    void requiresPersistenceAndAnExplicitEnabledFlag() {
        for (String profile : List.of("preview", "web-auth")) {
            runner.withPropertyValues("spring.profiles.active=" + profile,
                    "ROUTIQO_MODERATION_EXPIRY_MAINTENANCE_ENABLED=true")
                    .run(context -> assertThat(context).hasNotFailed()
                            .doesNotHaveBean(ContributionRestrictionExpiryMaintenanceJob.class)
                            .doesNotHaveBean("moderationExpiryTaskScheduler"));
        }
        runner.withPropertyValues("spring.profiles.active=persistence")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(ContributionRestrictionExpiryMaintenanceJob.class)
                        .doesNotHaveBean("moderationExpiryTaskScheduler"));
        runner.withPropertyValues("spring.profiles.active=persistence",
                "ROUTIQO_MODERATION_EXPIRY_MAINTENANCE_ENABLED=false")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(ContributionRestrictionExpiryMaintenanceJob.class)
                        .doesNotHaveBean("moderationExpiryTaskScheduler"));
    }

    @Test
    void enabledConfigurationRequiresCleanupAuthorityAndUsesDedicatedSchedule()
            throws Exception {
        runner.withPropertyValues("spring.profiles.active=persistence",
                "ROUTIQO_MODERATION_EXPIRY_MAINTENANCE_ENABLED=true")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(ContributionRestrictionExpiryMaintenanceJob.class)
                        .hasBean("moderationExpiryTaskScheduler")
                        .doesNotHaveBean("taskScheduler"));

        new ApplicationContextRunner()
                .withUserConfiguration(
                        ContributionRestrictionExpiryMaintenanceConfiguration.class)
                .withPropertyValues("spring.profiles.active=persistence",
                        "routiqo.moderation.expiry-maintenance-enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("ContributionRestrictionAuditCleanup");
                });

        Scheduled schedule = ContributionRestrictionExpiryMaintenanceJob.class
                .getMethod("runOnce").getAnnotation(Scheduled.class);
        assertThat(schedule.fixedDelay()).isEqualTo(60_000);
        assertThat(schedule.initialDelay()).isEqualTo(60_000);
        assertThat(schedule.scheduler()).isEqualTo("moderationExpiryTaskScheduler");
    }

    @Test
    void moderationLiveAndAuthSchedulersCoexistWithoutSharingThreads() {
        new ApplicationContextRunner()
                .withUserConfiguration(AuthInfrastructureConfiguration.class,
                        LiveExpiryMaintenanceConfiguration.class,
                        ContributionRestrictionExpiryMaintenanceConfiguration.class,
                        CoexistenceDependencies.class, SchedulerProbeConfiguration.class)
                .withPropertyValues("spring.profiles.active=persistence,web-auth",
                        "routiqo.live.expiry-maintenance-enabled=true",
                        "routiqo.moderation.expiry-maintenance-enabled=true",
                        "ROUTIQO_AUTH_RATE_SECRET=12345678901234567890123456789012")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasBean("taskScheduler")
                            .hasBean("liveExpiryTaskScheduler")
                            .hasBean("moderationExpiryTaskScheduler");
                    assertThat(context.getBeansOfType(ThreadPoolTaskScheduler.class)).hasSize(3);
                    assertThat(context.getBean("taskScheduler"))
                            .isNotSameAs(context.getBean("liveExpiryTaskScheduler"))
                            .isNotSameAs(context.getBean("moderationExpiryTaskScheduler"));
                    assertThat(context.getBean("liveExpiryTaskScheduler"))
                            .isNotSameAs(context.getBean("moderationExpiryTaskScheduler"));
                    SchedulerProbe probe = context.getBean(SchedulerProbe.class);
                    assertThat(probe.await()).isTrue();
                    assertThat(probe.authThread.get()).startsWith("auth-maintenance-");
                    assertThat(probe.liveThread.get()).startsWith("live-expiry-");
                    assertThat(probe.moderationThread.get()).startsWith("moderation-expiry-");
                });
    }

    @Test
    void authOwnsTheDefaultSchedulerWhenLiveMaintenanceIsOff() {
        for (String authProfile : List.of("web-auth", "native-auth")) {
            new ApplicationContextRunner()
                    .withUserConfiguration(AuthInfrastructureConfiguration.class,
                            ContributionRestrictionExpiryMaintenanceConfiguration.class,
                            CoexistenceDependencies.class,
                            AuthModerationProbeConfiguration.class)
                    .withPropertyValues("spring.profiles.active=persistence," + authProfile,
                            "routiqo.moderation.expiry-maintenance-enabled=true",
                            "ROUTIQO_AUTH_RATE_SECRET=12345678901234567890123456789012")
                    .run(context -> {
                        assertThat(context).hasNotFailed()
                                .hasBean("taskScheduler")
                                .hasBean("moderationExpiryTaskScheduler")
                                .doesNotHaveBean("liveExpiryTaskScheduler");
                        assertThat(context.getBeansOfType(ThreadPoolTaskScheduler.class)).hasSize(2);
                        AuthModerationProbe probe = context.getBean(AuthModerationProbe.class);
                        assertThat(probe.await()).isTrue();
                        assertThat(probe.authThread.get()).startsWith("auth-maintenance-");
                        assertThat(probe.moderationThread.get())
                                .startsWith("moderation-expiry-");
                    });
        }
    }

    @Test
    void authOnlyOwnsOneDefaultSchedulerAndStillRequiresPersistence() {
        new ApplicationContextRunner()
                .withUserConfiguration(AuthInfrastructureConfiguration.class,
                        CoexistenceDependencies.class)
                .withPropertyValues("spring.profiles.active=persistence,web-auth",
                        "ROUTIQO_AUTH_RATE_SECRET=12345678901234567890123456789012")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasBean("taskScheduler")
                            .doesNotHaveBean("liveExpiryTaskScheduler")
                            .doesNotHaveBean("moderationExpiryTaskScheduler");
                    assertThat(context.getBeansOfType(ThreadPoolTaskScheduler.class)).hasSize(1);
                });
        new ApplicationContextRunner()
                .withUserConfiguration(AuthInfrastructureConfiguration.class,
                        ContributionRestrictionExpiryMaintenanceConfiguration.class,
                        CoexistenceDependencies.class)
                .withPropertyValues("spring.profiles.active=web-auth",
                        "routiqo.moderation.expiry-maintenance-enabled=true",
                        "ROUTIQO_AUTH_RATE_SECRET=12345678901234567890123456789012")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean("taskScheduler")
                        .doesNotHaveBean("moderationExpiryTaskScheduler"));
    }

    @Configuration(proxyBeanMethods = false)
    static class CleanupDependency {
        @Bean ContributionRestrictionAuditCleanup cleanup() {
            return mock(ContributionRestrictionAuditCleanup.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CoexistenceDependencies {
        @Bean JdbcTemplate jdbcTemplate() { return new JdbcTemplate(mock(DataSource.class)); }
        @Bean ContributionRestrictionAuditCleanup cleanup() {
            return mock(ContributionRestrictionAuditCleanup.class);
        }
        @Bean LiveRouteContextExpiryMaintenance contexts() {
            return mock(LiveRouteContextExpiryMaintenance.class);
        }
        @Bean SignalStorageExpiryMaintenance signals() {
            return mock(SignalStorageExpiryMaintenance.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SchedulerProbeConfiguration {
        @Bean SchedulerProbe schedulerProbe() { return new SchedulerProbe(); }
    }

    static class SchedulerProbe {
        private final CountDownLatch authRan = new CountDownLatch(1);
        private final CountDownLatch liveRan = new CountDownLatch(1);
        private final CountDownLatch moderationRan = new CountDownLatch(1);
        private final AtomicReference<String> authThread = new AtomicReference<>();
        private final AtomicReference<String> liveThread = new AtomicReference<>();
        private final AtomicReference<String> moderationThread = new AtomicReference<>();

        @Scheduled(fixedDelay = 50, initialDelay = 10)
        public void runOnAuthScheduler() {
            authThread.set(Thread.currentThread().getName());
            authRan.countDown();
        }

        @Scheduled(fixedDelay = 50, initialDelay = 10,
                scheduler = "liveExpiryTaskScheduler")
        public void runOnLiveScheduler() {
            liveThread.set(Thread.currentThread().getName());
            liveRan.countDown();
        }

        @Scheduled(fixedDelay = 50, initialDelay = 10,
                scheduler = "moderationExpiryTaskScheduler")
        public void runOnModerationScheduler() {
            moderationThread.set(Thread.currentThread().getName());
            moderationRan.countDown();
        }

        boolean await() {
            try {
                return authRan.await(5, TimeUnit.SECONDS)
                        && liveRan.await(5, TimeUnit.SECONDS)
                        && moderationRan.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AuthModerationProbeConfiguration {
        @Bean AuthModerationProbe authModerationProbe() { return new AuthModerationProbe(); }
    }

    static class AuthModerationProbe {
        private final CountDownLatch authRan = new CountDownLatch(1);
        private final CountDownLatch moderationRan = new CountDownLatch(1);
        private final AtomicReference<String> authThread = new AtomicReference<>();
        private final AtomicReference<String> moderationThread = new AtomicReference<>();

        @Scheduled(fixedDelay = 50, initialDelay = 10)
        public void runOnAuthScheduler() {
            authThread.set(Thread.currentThread().getName());
            authRan.countDown();
        }

        @Scheduled(fixedDelay = 50, initialDelay = 10,
                scheduler = "moderationExpiryTaskScheduler")
        public void runOnModerationScheduler() {
            moderationThread.set(Thread.currentThread().getName());
            moderationRan.countDown();
        }

        boolean await() {
            try {
                return authRan.await(5, TimeUnit.SECONDS)
                        && moderationRan.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
