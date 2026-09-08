package com.routiqo.core.security;

import java.net.URI;
import java.util.Set;
import org.springframework.http.ResponseCookie;

public record BrowserAuthPolicy(String origin, boolean secureCookies) {
    public BrowserAuthPolicy {
        URI uri = URI.create(origin);
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()))
            throw new IllegalArgumentException("An exact browser origin without a path is required");
        boolean local = "http".equals(uri.getScheme()) && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
        if (!"https".equals(uri.getScheme()) && !local) throw new IllegalArgumentException("HTTPS origin required");
        if (!secureCookies && !local) throw new IllegalArgumentException("Insecure cookies are local-only");
    }
    public String cookieName(String name) { return secureCookies ? "__Host-" + name : name; }
    public String cookie(String name, String value, long seconds) {
        return ResponseCookie.from(cookieName(name), value).httpOnly(true).secure(secureCookies)
                .sameSite("Strict").path("/").maxAge(seconds).build().toString();
    }
}
