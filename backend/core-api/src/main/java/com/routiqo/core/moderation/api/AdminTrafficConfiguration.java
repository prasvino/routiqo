package com.routiqo.core.moderation.api;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.infrastructure.GoogleTokenVerifier;
import com.routiqo.core.moderation.infrastructure.AdminSessionService;
import com.routiqo.core.moderation.infrastructure.JdbcTrafficReview;
import com.routiqo.core.security.BrowserAuthPolicy;
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

@Configuration(proxyBeanMethods = false)
@Profile("web-auth & persistence & google-auth")
@ConditionalOnProperty(name = "ROUTIQO_V3_ADMIN_ENABLED", havingValue = "true")
public class AdminTrafficConfiguration {
    @Bean AdminSessionService adminSessionService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${ROUTIQO_ADMIN_GOOGLE_CLIENT_ID}") String clientId,
            @Value("${ROUTIQO_GOOGLE_CLIENT_ID}") String consumerClientId) {
        if (clientId.equals(consumerClientId)) throw new IllegalArgumentException("Admin Google audience must be separate");
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(3000); requests.setReadTimeout(3000);
        var decoder = NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .jwsAlgorithm(SignatureAlgorithm.RS256).restOperations(new RestTemplate(requests)).build();
        return new AdminSessionService(jdbc, manager,
                new GoogleTokenVerifier(decoder, clientId, Clock.systemUTC(), 600), Clock.systemUTC());
    }
    @Bean JdbcTrafficReview jdbcTrafficReview(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcTrafficReview(jdbc, manager, Clock.systemUTC());
    }
    record AdminTrafficSettings(BrowserAuthPolicy policy) {}
    @Bean AdminTrafficSettings adminTrafficSettings(@Value("${ROUTIQO_ADMIN_ORIGIN}") String origin,
            @Value("${ROUTIQO_WEB_ORIGIN}") String consumerOrigin,
            @Value("${ROUTIQO_AUTH_SECURE_COOKIES:true}") boolean secure) {
        var adminPolicy = new BrowserAuthPolicy(origin, secure);
        new BrowserAuthPolicy(consumerOrigin, secure);
        if (sameBrowserOrigin(origin, consumerOrigin))
            throw new IllegalArgumentException("Admin origin must be separate");
        return new AdminTrafficSettings(adminPolicy);
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
    @Bean @Order(0) SecurityFilterChain adminSecurity(HttpSecurity http, AdminTrafficSettings settings,
            AuthRateGate rates) throws Exception {
        BrowserAuthPolicy adminBrowserAuthPolicy = settings.policy();
        var csrf = new CookieCsrfTokenRepository();
        csrf.setCookieName(adminBrowserAuthPolicy.cookieName("routiqo_admin_csrf"));
        csrf.setCookiePath("/");
        csrf.setCookieCustomizer(cookie -> cookie.httpOnly(true).secure(adminBrowserAuthPolicy.secureCookies()).sameSite("Strict"));
        return http.securityMatcher("/api/v1/admin/**")
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .csrf(c -> c.csrfTokenRepository(csrf))
                .addFilterBefore(new AdminTrafficGuard(adminBrowserAuthPolicy, rates), CsrfFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/admin/auth/**", "/api/v1/admin/community-traffic/reports",
                                "/api/v1/admin/community-traffic/reports/**").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, error) -> response.setStatus(401))
                        .accessDeniedHandler((request, response, error) -> response.setStatus(403)))
                .build();
    }
}
