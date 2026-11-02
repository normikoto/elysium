package dev.biribiri.elysium.auth.internal;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Sends people to the login page when they're authorizing an application. The other OAuth endpoints are called by
 * clients rather than people, so they get a JSON error.
 */
class ClientOrLoginEntryPoint implements AuthenticationEntryPoint {

    private final AuthenticationEntryPoint login;
    private final OAuthResponses responses;

    ClientOrLoginEntryPoint(AuthenticationEntryPoint login, OAuthResponses responses) {
        this.login = login;
        this.responses = responses;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException, ServletException {
        if (SecurityConfig.AUTHORIZE_PATH.equals(request.getRequestURI())) {
            login.commence(request, response, failure);
        } else {
            responses.clientAuthenticationFailure(request, response, failure);
        }
    }
}
