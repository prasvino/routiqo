package com.routiqo.core.moderation.api;

import com.routiqo.core.security.FeatureFlags;
import com.routiqo.core.security.ConditionalOnExactlyTrue;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.infrastructure.GoogleTokenVerifier;
import com.routiqo.core.moderation.infrastructure.AdminSessionService;
import com.routiqo.core.security.BrowserAuthPolicy;
import com.routiqo.core.security.ClientAddressResolver;
import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;

/**
 * The admin base (ADR 0075): separate origin and Google audience, sign-in, renewable sessions and the
 * {@code /api/v1/admin/**} chain. Each admin feature adds its own flag on top of ROUTIQO_ADMIN_ENABLED.
 */
@Configuration(proxyBeanMethods = false)
@Profile("web-auth & persistence & google-auth")
@ConditionalOnExactlyTrue({"ROUTIQO_ADMIN_ENABLED"})
public class AdminAccessConfiguration {
    @Bean AdminSessionService adminSessionService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${ROUTIQO_ADMIN_GOOGLE_CLIENT_ID}") String clientId,
            @Value("${ROUTIQO_GOOGLE_CLIENT_ID}") String consumerClientId) {
        if (clientId.equals(consumerClientId)) throw new IllegalArgumentException("Admin Google audience must be separate");
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(3000); requests.setReadTimeout(3000);
        var decoder = NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .jwsAlgorithm(SignatureAlgorithm.RS256).restOperations(new RestTemplate(requests)).build();
        return new AdminSessionService(jdbc, manager,
                new GoogleTokenVerifier(decoder, clientId, Clock.systemUTC(), 600));
    }
    record AdminSettings(BrowserAuthPolicy policy) {}
    @Bean AdminSettings adminSettings(@Value("${ROUTIQO_ADMIN_ORIGIN}") String origin,
            @Value("${ROUTIQO_WEB_ORIGIN}") String consumerOrigin,
            @Value("${ROUTIQO_AUTH_SECURE_COOKIES:true}") boolean secure) {
        var adminPolicy = new BrowserAuthPolicy(origin, secure);
        new BrowserAuthPolicy(consumerOrigin, secure);
        if (sameBrowserOrigin(origin, consumerOrigin))
            throw new IllegalArgumentException("Admin origin must be separate");
        return new AdminSettings(adminPolicy);
    }
    private static boolean sameBrowserOrigin(String left, String right) {
        URI a = URI.create(left), b = URI.create(right);
        return a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && effectivePort(a) == effectivePort(b);
    }
    private static int effectivePort(URI uri) {
        return uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
    }
    @Bean @Order(0) SecurityFilterChain adminSecurity(HttpSecurity http, AdminSettings settings,
            AuthRateGate rates, ClientAddressResolver clients,
            @Value("${ROUTIQO_V3_ADMIN_ENABLED:false}") String trafficAdminEnabledFlag,
            @Value("${ROUTIQO_V3_GRANT_ADMIN_ENABLED:false}") String grantAdminEnabledFlag,
            @Value("${ROUTIQO_SPOTS_ADMIN_ENABLED:false}") String spotsAdminEnabledFlag,
            @Value("${ROUTIQO_SPOTS_GRANT_ADMIN_ENABLED:false}") String spotGrantAdminEnabledFlag) throws Exception {
        // Each feature's paths open only with its own exact-true flag; everything else is denied.
        boolean trafficAdminEnabled = FeatureFlags.enabled(trafficAdminEnabledFlag);
        boolean grantAdminEnabled = trafficAdminEnabled && FeatureFlags.enabled(grantAdminEnabledFlag);
        boolean spotsAdminEnabled = FeatureFlags.enabled(spotsAdminEnabledFlag);
        boolean spotGrantAdminEnabled = FeatureFlags.enabled(spotGrantAdminEnabledFlag);
        BrowserAuthPolicy adminBrowserAuthPolicy = settings.policy();
        var csrf = new CookieCsrfTokenRepository();
        csrf.setCookieName(adminBrowserAuthPolicy.cookieName("routiqo_admin_csrf"));
        csrf.setCookiePath("/");
        csrf.setCookieCustomizer(cookie -> cookie.httpOnly(true).secure(adminBrowserAuthPolicy.secureCookies()).sameSite("Strict"));
        return http.securityMatcher("/api/v1/admin/**")
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .csrf(c -> c.csrfTokenRepository(csrf))
                .addFilterBefore(new AdminTrafficGuard(adminBrowserAuthPolicy, rates, clients), CsrfFilter.class)
                .authorizeHttpRequests(a -> {
                    a.requestMatchers("/api/v1/admin/auth/**").permitAll();
                    if (trafficAdminEnabled) a.requestMatchers("/api/v1/admin/community-traffic/reports",
                            "/api/v1/admin/community-traffic/reports/**").permitAll();
                    if (grantAdminEnabled) a.requestMatchers("/api/v1/admin/traffic-grants/**").permitAll();
                    if (spotsAdminEnabled) a.requestMatchers("/api/v1/admin/spots/**").permitAll();
                    if (spotGrantAdminEnabled) a.requestMatchers("/api/v1/admin/spot-grants/**").permitAll();
                    a.anyRequest().denyAll();
                })
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, error) -> response.setStatus(401))
                        .accessDeniedHandler((request, response, error) -> response.setStatus(403)))
                .build();
    }
}
