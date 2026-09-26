package com.routiqo.core.security;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration(proxyBeanMethods = false)
@Profile("native-auth")
public class NativeAuthConfiguration {
    @Bean @Order(0) SecurityFilterChain nativeAuthSecurity(HttpSecurity http, AuthRateGate rates,
            GoogleSessionService sessions,
            @Value("${ROUTIQO_NATIVE_LIVE_CONSENT_API_ENABLED:false}") boolean consentEnabled,
            @Value("${ROUTIQO_NATIVE_ROUTING_API_ENABLED:false}") boolean routingEnabled,
            @Value("${ROUTIQO_NATIVE_LIVE_ROUTE_BINDING_API_ENABLED:false}") boolean bindingEnabled,
            @Value("${ROUTIQO_SPOTS_API_ENABLED:}") String spotsEnabledFlag)
            throws Exception {
        boolean spotsEnabled = FeatureFlags.enabled(spotsEnabledFlag);
        return http.securityMatcher("/api/v1/native/**")
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .csrf(c -> c.disable())
                .addFilterBefore(new NativeAuthGuard(rates, sessions, consentEnabled, routingEnabled,
                        bindingEnabled, spotsEnabled),
                        AuthorizationFilter.class)
                .authorizeHttpRequests(a -> {
                    if (spotsEnabled) {
                        a.requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/native/spots/catalog").permitAll();
                        a.requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/v1/native/spots/activity").permitAll();
                    }
                    if (bindingEnabled) {
                        a.requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/native/journeys/*/route-context").permitAll();
                        a.requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/v1/native/journeys/*/route-context").permitAll();
                    }
                    if (routingEnabled) {
                        a.requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/v1/native/routes", "/api/v1/native/routes/places").permitAll();
                    }
                    if (consentEnabled) {
                        a.requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/native/journeys/*/consent").permitAll();
                        a.requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/v1/native/journeys/*/consent").permitAll();
                    }
                    a
                    .requestMatchers(org.springframework.http.HttpMethod.POST,
                        "/api/v1/native/auth/google/challenge", "/api/v1/native/auth/google/exchange",
                        "/api/v1/native/auth/session/renew", "/api/v1/native/auth/logout",
                        "/api/v1/native/auth/account/delete").permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/native/auth/session").permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.GET,
                        "/api/v1/native/journeys", "/api/v1/native/journeys/*",
                        "/api/v1/native/journeys/*/journal").permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.POST,
                        "/api/v1/native/journeys", "/api/v1/native/journeys/history",
                        "/api/v1/native/journeys/*/complete", "/api/v1/native/journeys/*/journal").permitAll()
                    .anyRequest().denyAll();
                })
                .exceptionHandling(e -> e
                    .authenticationEntryPoint((request, response, error) -> response.setStatus(401))
                    .accessDeniedHandler((request, response, error) -> response.setStatus(403)))
                .build();
    }
}
