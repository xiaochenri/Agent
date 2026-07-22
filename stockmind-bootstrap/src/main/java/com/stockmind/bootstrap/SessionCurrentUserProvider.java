package com.stockmind.bootstrap;

import com.agent.javascope.user.application.UserAuthenticationService;
import com.agent.javascope.user.identity.CurrentUserProvider;
import com.agent.javascope.user.identity.UnauthenticatedException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Resolves the current user from the HttpOnly session cookie. */
@Component
public class SessionCurrentUserProvider implements CurrentUserProvider {
    public static final String SESSION_COOKIE = "STOCKMIND_SESSION";
    private final UserAuthenticationService authentication;

    public SessionCurrentUserProvider(UserAuthenticationService authentication) { this.authentication = authentication; }

    @Override public String currentUserId() {
        String token = token(currentRequest());
        if (token == null || token.isBlank()) throw new UnauthenticatedException();
        try { return authentication.currentUser(token).id(); }
        catch (IllegalArgumentException error) { throw new UnauthenticatedException(); }
    }

    static String token(HttpServletRequest request) {
        if (request == null || request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) if (SESSION_COOKIE.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    private HttpServletRequest currentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw new UnauthenticatedException();
        }
        return attributes.getRequest();
    }
}
