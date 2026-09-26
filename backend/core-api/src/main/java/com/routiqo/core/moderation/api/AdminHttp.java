package com.routiqo.core.moderation.api;

import com.routiqo.core.identity.application.AuthRateGate;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Request checks shared by the admin controllers. */
final class AdminHttp {
    private AdminHttp() {}

    static final class Limited extends RuntimeException {}
    static final class Unavailable extends RuntimeException {}

    static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new IllegalArgumentException("Invalid query");
    }

    static void emptyBody(HttpServletRequest request) {
        try {
            byte[] body = request.getInputStream().readNBytes(3);
            if (!(body.length == 0 || new String(body, StandardCharsets.US_ASCII).equals("{}")))
                throw new IllegalArgumentException("Invalid request");
        } catch (IOException error) { throw new IllegalArgumentException("Invalid request"); }
    }

    /** Per-operator rate limit; a rate-store failure is Unavailable, never an allow. */
    static void allow(AuthRateGate rates, UUID operator, String category, int limit) {
        final boolean allowed;
        try { allowed = rates.allow(operator.toString(), category, limit); }
        catch (RuntimeException unavailable) { throw new Unavailable(); }
        if (!allowed) throw new Limited();
    }
}
