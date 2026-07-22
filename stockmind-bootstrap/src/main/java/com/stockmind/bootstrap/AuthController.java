package com.stockmind.bootstrap;

import com.agent.javascope.user.application.UserAuthenticationService;
import com.agent.javascope.user.identity.UserAccount;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    private final UserAuthenticationService authentication;
    public AuthController(UserAuthenticationService authentication) { this.authentication = authentication; }

    @PostMapping(path = "/api/v1/auth/register", produces = MediaType.APPLICATION_JSON_VALUE)
    public UserAccount register(@RequestBody Credentials request, HttpServletResponse response) {
        UserAuthenticationService.LoginResult result = authentication.register(request.username(), request.password(), request.displayName());
        writeSession(response, result.sessionToken()); return result.user();
    }

    @PostMapping(path = "/api/v1/auth/login", produces = MediaType.APPLICATION_JSON_VALUE)
    public UserAccount login(@RequestBody Credentials request, HttpServletResponse response) {
        UserAuthenticationService.LoginResult result = authentication.login(request.username(), request.password());
        writeSession(response, result.sessionToken()); return result.user();
    }

    @PostMapping(path = "/api/v1/auth/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        authentication.logout(SessionCurrentUserProvider.token(request));
        Cookie cookie = new Cookie(SessionCurrentUserProvider.SESSION_COOKIE, ""); cookie.setHttpOnly(true); cookie.setPath("/"); cookie.setMaxAge(0); response.addCookie(cookie);
    }

    private void writeSession(HttpServletResponse response, String token) {
        Cookie cookie = new Cookie(SessionCurrentUserProvider.SESSION_COOKIE, token);
        cookie.setHttpOnly(true); cookie.setPath("/"); cookie.setMaxAge(14 * 24 * 60 * 60); response.addCookie(cookie);
    }
    public record Credentials(String username, String password, String displayName) { }
}
