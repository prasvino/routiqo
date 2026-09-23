package com.routiqo.core.moderation.api;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.security.BrowserAuthPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

final class AdminTrafficGuard extends OncePerRequestFilter {
    private final BrowserAuthPolicy policy;
    private final AuthRateGate rates;
    AdminTrafficGuard(BrowserAuthPolicy policy, AuthRateGate rates) { this.policy = policy; this.rates = rates; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if ("cross-site".equals(request.getHeader("Sec-Fetch-Site"))
                || ("POST".equals(request.getMethod()) && !policy.origin().equals(request.getHeader("Origin")))) {
            response.setStatus(403); return;
        }
        String path = request.getRequestURI();
        String category = path.endsWith("/challenge") ? "admin-challenge"
                : path.endsWith("/exchange") ? "admin-exchange" : "admin-transport";
        try {
            if (!rates.allow(request.getRemoteAddr(), category, category.equals("admin-transport") ? 100 : 10)) {
                response.setHeader("Retry-After", "60"); response.setStatus(429); return;
            }
        } catch (RuntimeException unavailable) { response.setStatus(503); return; }
        if (!"POST".equals(request.getMethod())) { chain.doFilter(request, response); return; }
        String type = request.getContentType();
        if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            response.setStatus(415); return;
        }
        byte[] body = request.getInputStream().readNBytes(20 * 1024 + 1);
        if (body.length > 20 * 1024) { response.setStatus(413); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
            @Override public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
        }, response);
    }
}
