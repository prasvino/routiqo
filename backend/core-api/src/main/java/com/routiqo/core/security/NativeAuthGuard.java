package com.routiqo.core.security;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

public final class NativeAuthGuard extends OncePerRequestFilter {
    public static final String CREDENTIAL_ATTRIBUTE = NativeAuthGuard.class.getName() + ".credential";
    private static final int MAX_BODY_BYTES = 20 * 1024;
    private static final Pattern BEARER = Pattern.compile("Bearer ([A-Za-z0-9_-]{43})");
    private final AuthRateGate rates;
    private final GoogleSessionService sessions;

    public NativeAuthGuard(AuthRateGate rates, GoogleSessionService sessions) {
        this.rates = rates;
        this.sessions = sessions;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (request.getQueryString() != null || hasHeader(request, HttpHeaders.COOKIE)
                || hasHeader(request, HttpHeaders.ORIGIN) || hasHeader(request, "Sec-Fetch-Site")) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        String path = request.getRequestURI();
        boolean challenge = "POST".equals(request.getMethod())
                && "/api/v1/native/auth/google/challenge".equals(path);
        boolean exchange = "POST".equals(request.getMethod())
                && "/api/v1/native/auth/google/exchange".equals(path);
        boolean logout = "POST".equals(request.getMethod())
                && "/api/v1/native/auth/logout".equals(path);
        boolean protectedOperation = ("GET".equals(request.getMethod())
                    && "/api/v1/native/auth/session".equals(path))
                || ("POST".equals(request.getMethod()) && ("/api/v1/native/auth/session/renew".equals(path)
                    || "/api/v1/native/auth/logout".equals(path)
                    || "/api/v1/native/auth/account/delete".equals(path)));
        if (!challenge && !exchange && !protectedOperation) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        var authorization = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        if (challenge || exchange) {
            if (!authorization.isEmpty()) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        } else {
            if (authorization.size() != 1) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            var match = BEARER.matcher(authorization.getFirst());
            if (!match.matches()) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            request.setAttribute(CREDENTIAL_ATTRIBUTE, match.group(1));
        }

        String category = challenge ? "native-challenge" : exchange ? "native-exchange" : "native-other";
        int limit = challenge ? 10 : exchange ? 20 : 120;
        if (!rates.allow(request.getRemoteAddr(), category, limit)) {
            response.setHeader(HttpHeaders.RETRY_AFTER, "60");
            response.setStatus(429);
            return;
        }
        if (protectedOperation) {
            String credential = (String) request.getAttribute(CREDENTIAL_ATTRIBUTE);
            String accountId = null;
            if (logout) {
                accountId = sessions.revocationAccount(credential).map(Object::toString).orElse(null);
            } else {
                try {
                    accountId = sessions.authenticate(credential).toString();
                } catch (SecurityException denied) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
            }
            if (accountId != null && !rates.allow(accountId, "native-account", 120)) {
                response.setHeader(HttpHeaders.RETRY_AFTER, "60");
                response.setStatus(429);
                return;
            }
        }

        if ("POST".equals(request.getMethod())) {
            String contentType = request.getContentType();
            if (contentType == null
                    || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                response.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
                return;
            }
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                return;
            }
            chain.doFilter(replayable(request, body), response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean hasHeader(HttpServletRequest request, String name) {
        return request.getHeaderNames() != null
                && Collections.list(request.getHeaderNames()).stream().anyMatch(name::equalsIgnoreCase);
    }

    private static HttpServletRequest replayable(HttpServletRequest request, byte[] body) {
        return new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) {
                        throw new UnsupportedOperationException();
                    }
                };
            }

            @Override public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
        };
    }
}
