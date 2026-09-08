package com.routiqo.core.security;

import com.routiqo.core.identity.application.AuthRateGate;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.web.filter.OncePerRequestFilter;

public final class BrowserAuthGuard extends OncePerRequestFilter {
    private final BrowserAuthPolicy policy;
    private final AuthRateGate rates;
    public BrowserAuthGuard(BrowserAuthPolicy policy, AuthRateGate rates) { this.policy = policy; this.rates = rates; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if ("cross-site".equals(request.getHeader("Sec-Fetch-Site")) ||
                ("POST".equals(request.getMethod()) && !policy.origin().equals(request.getHeader("Origin")))) {
            response.setStatus(403); return;
        }
        String path = request.getRequestURI();
        String category = path.endsWith("/challenge") ? "challenge" : path.endsWith("/exchange") ? "exchange" : "other";
        int limit = category.equals("challenge") ? 10 : category.equals("exchange") ? 20 : 120;
        if (!rates.allow(request.getRemoteAddr(), category, limit)) {
            response.setHeader("Retry-After", "60"); response.setStatus(429); return;
        }
        if ("POST".equals(request.getMethod())) {
            String contentType = request.getContentType();
            if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                response.setStatus(415); return;
            }
            byte[] body = request.getInputStream().readNBytes(20 * 1024 + 1);
            if (body.length > 20 * 1024) { response.setStatus(413); return; }
            var wrapped = new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    var input = new ByteArrayInputStream(body);
                    return new ServletInputStream() {
                        @Override public int read() { return input.read(); }
                        @Override public boolean isFinished() { return input.available() == 0; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                    };
                }
                @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8)); }
            };
            chain.doFilter(wrapped, response); return;
        }
        chain.doFilter(request, response);
    }
}

