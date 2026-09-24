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
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

public final class NativeAuthGuard extends OncePerRequestFilter {
    public static final String CREDENTIAL_ATTRIBUTE = NativeAuthGuard.class.getName() + ".credential";
    public static final String ACCOUNT_ATTRIBUTE = NativeAuthGuard.class.getName() + ".account";
    private static final int MAX_BODY_BYTES = 20 * 1024;
    private static final Pattern BEARER = Pattern.compile("Bearer ([A-Za-z0-9_-]{43})");
    private final AuthRateGate rates;
    private final GoogleSessionService sessions;
    private final boolean consentEnabled;
    private final boolean routingEnabled;

    public NativeAuthGuard(AuthRateGate rates, GoogleSessionService sessions, boolean consentEnabled,
            boolean routingEnabled) {
        this.rates = rates;
        this.sessions = sessions;
        this.consentEnabled = consentEnabled;
        this.routingEnabled = routingEnabled;
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
        boolean consent = consentEnabled
                && ("GET".equals(request.getMethod()) || "POST".equals(request.getMethod()))
                && path.matches("/api/v1/native/journeys/[a-fA-F0-9-]{36}/consent");
        boolean routing = routingEnabled && "POST".equals(request.getMethod())
                && ("/api/v1/native/routes".equals(path) || "/api/v1/native/routes/places".equals(path));
        boolean journey = ("GET".equals(request.getMethod()) &&
                    ("/api/v1/native/journeys".equals(path)
                        || path.matches("/api/v1/native/journeys/[a-fA-F0-9-]{36}")
                        || path.matches("/api/v1/native/journeys/[a-fA-F0-9-]{36}/journal")))
                || ("POST".equals(request.getMethod()) &&
                    ("/api/v1/native/journeys".equals(path)
                        || "/api/v1/native/journeys/history".equals(path)
                        || path.matches("/api/v1/native/journeys/[a-fA-F0-9-]{36}/(?:complete|journal)")));
        boolean protectedOperation = ("GET".equals(request.getMethod())
                    && "/api/v1/native/auth/session".equals(path))
                || ("POST".equals(request.getMethod()) && ("/api/v1/native/auth/session/renew".equals(path)
                    || "/api/v1/native/auth/logout".equals(path)
                    || "/api/v1/native/auth/account/delete".equals(path)));
        if (!challenge && !exchange && !protectedOperation && !journey && !consent && !routing) {
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
        final boolean peerAllowed;
        try { peerAllowed = rates.allow(request.getRemoteAddr(), category, limit); }
        catch (RuntimeException unavailable) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        if (!peerAllowed) {
            response.setHeader(HttpHeaders.RETRY_AFTER, "60");
            response.setStatus(429);
            return;
        }
        if (protectedOperation || journey || consent || routing) {
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
                } catch (DataAccessException | TransactionException unavailable) {
                    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    return;
                }
            }
            final boolean accountAllowed;
            try { accountAllowed = accountId == null || rates.allow(accountId, "native-account", 120); }
            catch (RuntimeException unavailable) {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
            if (!accountAllowed) {
                response.setHeader(HttpHeaders.RETRY_AFTER, "60");
                response.setStatus(429);
                return;
            }
            if (journey || consent || routing) request.setAttribute(ACCOUNT_ATTRIBUTE, accountId);
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
