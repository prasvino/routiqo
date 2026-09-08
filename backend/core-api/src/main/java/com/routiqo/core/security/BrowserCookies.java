package com.routiqo.core.security;

import jakarta.servlet.http.HttpServletRequest;

public final class BrowserCookies {
    private BrowserCookies() {}
    public static String read(HttpServletRequest request, BrowserAuthPolicy policy, String name) {
        String value = null;
        if (request.getCookies() != null) for (var cookie : request.getCookies()) {
            if (policy.cookieName(name).equals(cookie.getName())) {
                if (value != null) throw new SecurityException("Ambiguous authentication cookie");
                value = cookie.getValue();
            }
        }
        return value;
    }
}
