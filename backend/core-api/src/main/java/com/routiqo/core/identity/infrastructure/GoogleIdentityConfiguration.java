package com.routiqo.core.identity.infrastructure;

import com.routiqo.core.identity.application.GoogleIdentityVerifier;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("google-auth")
public class GoogleIdentityConfiguration {
    @Bean GoogleIdentityVerifier googleIdentityVerifier(@Value("${ROUTIQO_GOOGLE_CLIENT_ID}") String clientId) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(3000); requests.setReadTimeout(3000);
        var decoder = NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(requests)).build();
        return new GoogleTokenVerifier(decoder, clientId, Clock.systemUTC());
    }
}
