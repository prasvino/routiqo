package com.routiqo.core.security;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration(proxyBeanMethods = false)
@Profile("native-auth")
public class NativeAuthConfiguration {
    @Bean @Order(0) SecurityFilterChain nativeAuthSecurity(HttpSecurity http, AuthRateGate rates,
            GoogleSessionService sessions) throws Exception {
        return http.securityMatcher("/api/v1/native/auth/**")
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .csrf(c -> c.disable())
                .addFilterBefore(new NativeAuthGuard(rates, sessions), AuthorizationFilter.class)
                .authorizeHttpRequests(a -> a
                    .requestMatchers(org.springframework.http.HttpMethod.POST,
                        "/api/v1/native/auth/google/challenge", "/api/v1/native/auth/google/exchange",
                        "/api/v1/native/auth/session/renew", "/api/v1/native/auth/logout",
                        "/api/v1/native/auth/account/delete").permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/native/auth/session").permitAll()
                    .anyRequest().denyAll())
                .exceptionHandling(e -> e
                    .authenticationEntryPoint((request, response, error) -> response.setStatus(401))
                    .accessDeniedHandler((request, response, error) -> response.setStatus(403)))
                .build();
    }
}
