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
    @Bean @Order(1) SecurityFilterChain browserAuthSecurity(HttpSecurity http, BrowserAuthPolicy policy,
            AuthRateGate rates,
            @Value("${ROUTIQO_LIVE_CONSENT_API_ENABLED:false}") boolean consentEnabled,
            @Value("${ROUTIQO_LIVE_ROUTE_BINDING_API_ENABLED:false}") boolean routeBindingEnabled,
            @Value("${ROUTIQO_LIVE_SIGNAL_API_ENABLED:false}") boolean signalEnabled,
            @Value("${ROUTIQO_LIVE_CHOICE_API_ENABLED:false}") boolean choiceEnabled,
            @Value("${ROUTIQO_PUBLIC_SIGNAL_INTENT_API_ENABLED:false}") boolean publicIntentEnabled,
            @Value("${ROUTIQO_PUBLIC_SIGNAL_INTENT_SHARE_ENABLED:false}") boolean publicIntentShareEnabled) throws Exception {
        var csrf = new CookieCsrfTokenRepository();
        csrf.setCookieName(policy.cookieName("routiqo_csrf")); csrf.setCookiePath("/");
        csrf.setCookieCustomizer(cookie -> cookie.httpOnly(true).secure(policy.secureCookies()).sameSite("Strict"));
        return http.securityMatcher("/api/v1/auth/**", "/api/v1/journeys", "/api/v1/journeys/**", "/api/v1/routes", "/api/v1/routes/**", "/api/v1/public-intents")
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .csrf(c -> c.csrfTokenRepository(csrf))
                .addFilterBefore(new BrowserAuthGuard(policy, rates), CsrfFilter.class)
                .authorizeHttpRequests(a -> {
                    a.requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/routes", "/api/v1/routes/places").permitAll();
                    a.requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/auth/csrf", "/api/v1/auth/session").permitAll();
                    a.requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/journeys", "/api/v1/journeys/*", "/api/v1/journeys/*/journal").permitAll();
                    a.requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/journeys", "/api/v1/journeys/*/complete", "/api/v1/journeys/*/journal").permitAll();
                    if (consentEnabled) {
                        a.requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/journeys/*/consent").permitAll();
                        a.requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/v1/journeys/*/consent").permitAll();
                    }
                    if (routeBindingEnabled) {
                        a.requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/journeys/*/route-context").permitAll();
                        a.requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/v1/journeys/*/route-context").permitAll();
                    }
                    if (signalEnabled) {
                        a.requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/v1/journeys/*/signal-commands",
                                "/api/v1/journeys/*/signal-commands/*/stop",
                                "/api/v1/journeys/*/signals/*",
                                "/api/v1/journeys/*/signals/*/withdraw").permitAll();
                    }
                    if (signalEnabled && choiceEnabled) {
                        a.requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/journeys/*/signal-choices").permitAll();
                        a.requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/v1/journeys/*/signal-commands/expected-context").permitAll();
                    }
                    if (publicIntentEnabled) {
                        a.requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/public-intents").permitAll();
                        a.requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/v1/journeys/*/signals/*/public-intent/stop").permitAll();
                        if (publicIntentShareEnabled) {
                            a.requestMatchers(org.springframework.http.HttpMethod.POST,
                                    "/api/v1/journeys/*/signals/*/public-intent").permitAll();
                        }
                    }
                    a.requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/auth/google/challenge", "/api/v1/auth/google/exchange", "/api/v1/auth/logout", "/api/v1/auth/session/renew", "/api/v1/auth/account/delete").permitAll();
                    a.anyRequest().denyAll();
                })
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, error) -> response.setStatus(401))
                    .accessDeniedHandler((request, response, error) -> response.setStatus(403)))
                .build();
    }
}
