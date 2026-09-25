package com.routiqo.core.security;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR 0065: community and public LIVE switches fail closed on anything but the exact value "true". */
class FeatureFlagsTest {
    static final String[] AMBIGUOUS = {
        null, "", " ", "false", "TRUE", "True", "tRuE", " true", "true ", "true\n", "1", "yes", "on", "enabled", "\"true\""
    };

    @Test void onlyExactLowercaseTrueEnables() {
        assertThat(FeatureFlags.enabled("true")).isTrue();
        for (String value : AMBIGUOUS)
            assertThat(FeatureFlags.enabled(value)).as("value %s", value).isFalse();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnExactlyTrue({"FLAG_A", "FLAG_B"})
    static class Gated {
        @Bean String gatedMarker() { return "loaded"; }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Gated.class);

    @Test void conditionRequiresEveryFlagExactlyTrue() {
        runner.run(context -> assertThat(context).doesNotHaveBean("gatedMarker"));
        runner.withPropertyValues("FLAG_A=true").run(context ->
                assertThat(context).doesNotHaveBean("gatedMarker"));
        runner.withPropertyValues("FLAG_A=true", "FLAG_B=true").run(context ->
                assertThat(context).hasBean("gatedMarker"));
        // Exact values through a property source: the runner's "key=value" parsing would trim them.
        for (String value : Arrays.copyOfRange(AMBIGUOUS, 1, AMBIGUOUS.length)) {
            runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("flags", Map.of("FLAG_A", "true", "FLAG_B", value))))
                    .run(context -> assertThat(context).as("FLAG_B=[%s]", value).doesNotHaveBean("gatedMarker"));
        }
    }
}
