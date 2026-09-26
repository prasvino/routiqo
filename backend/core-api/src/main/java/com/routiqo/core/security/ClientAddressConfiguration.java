package com.routiqo.core.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** One rate-limit client address source for the native, browser and admin guards (ADR 0074). */
@Configuration(proxyBeanMethods = false)
@Profile("native-auth | web-auth")
public class ClientAddressConfiguration {
    /** Empty (the default) keeps the socket peer; otherwise literal CIDRs of the load balancer only. */
    @Bean ClientAddressResolver clientAddressResolver(@Value("${ROUTIQO_TRUSTED_PROXY_CIDRS:}") String trustedRanges,
            @Value("${server.forward-headers-strategy:none}") String forwardHeaders) {
        // With container forwarding on, getRemoteAddr() would already come from a client-written header.
        if (!"none".equalsIgnoreCase(forwardHeaders.trim()))
            throw new IllegalStateException("Rate-limit client addresses require server.forward-headers-strategy=none");
        return new ClientAddressResolver(trustedRanges);
    }
}
