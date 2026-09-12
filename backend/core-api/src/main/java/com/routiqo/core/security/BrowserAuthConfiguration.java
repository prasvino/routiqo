package com.routiqo.core.security;

import com.routiqo.core.identity.application.AuthRateGate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;

@Configuration(proxyBeanMethods = false)
@Profile("web-auth")
public class BrowserAuthConfiguration {
    @Bean BrowserAuthPolicy browserAuthPolicy(@Value("${ROUTIQO_WEB_ORIGIN}") String origin,
            @Value("${ROUTIQO_AUTH_SECURE_COOKIES:true}") boolean secure) { return new BrowserAuthPolicy(origin, secure); }
    @Bean @Order(1) SecurityFilterChain browserAuthSecurity(HttpSecurity http, BrowserAuthPolicy policy, AuthRateGate rates) throws Exception {
        var csrf = new CookieCsrfTokenRepository();
        csrf.setCookieName(policy.cookieName("routiqo_csrf")); csrf.setCookiePath("/");
        csrf.setCookieCustomizer(cookie -> cookie.httpOnly(true).secure(policy.secureCookies()).sameSite("Strict"));
        return http.securityMatcher("/api/v1/auth/**", "/api/v1/journeys", "/api/v1/journeys/**", "/api/v1/routes", "/api/v1/routes/**")
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .csrf(c -> c.csrfTokenRepository(csrf))
                .addFilterBefore(new BrowserAuthGuard(policy, rates), CsrfFilter.class)
                .authorizeHttpRequests(a -> a
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/routes", "/api/v1/routes/places").permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/auth/csrf", "/api/v1/auth/session").permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/journeys", "/api/v1/journeys/*", "/api/v1/journeys/*/journal").permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/journeys", "/api/v1/journeys/*/complete", "/api/v1/journeys/*/journal").permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/auth/google/challenge", "/api/v1/auth/google/exchange", "/api/v1/auth/logout", "/api/v1/auth/session/renew", "/api/v1/auth/account/delete").permitAll()
                    .anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, error) -> response.setStatus(401))
                    .accessDeniedHandler((request, response, error) -> response.setStatus(403)))
                .build();
    }
}
