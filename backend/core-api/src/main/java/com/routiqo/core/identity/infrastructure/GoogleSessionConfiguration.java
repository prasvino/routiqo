package com.routiqo.core.identity.infrastructure;

import com.routiqo.core.identity.application.*;
import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@Profile("persistence & google-auth")
public class GoogleSessionConfiguration {
    @Bean GoogleSessionService googleSessionService(SessionStore store, GoogleIdentityVerifier verifier) {
        return new GoogleSessionService(store, verifier, Clock.systemUTC());
    }
}
