package com.routiqo.core.identity.infrastructure;

import com.routiqo.core.identity.application.GoogleIdentityVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class GoogleIdentityConfigurationTest {
    final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(GoogleIdentityConfiguration.class)
            .withPropertyValues("spring.profiles.active=google-auth");
    @Test void configuredVerifierStartsWithoutContactingGoogle() {
        context.withPropertyValues("ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com")
                .run(c -> assertThat(c).hasNotFailed().hasSingleBean(GoogleIdentityVerifier.class));
    }
    @Test void missingClientFailsClosed() { context.run(c -> assertThat(c).hasFailed()); }
}
