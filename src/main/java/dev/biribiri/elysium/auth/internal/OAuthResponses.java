package dev.biribiri.elysium.auth.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Writes OAuth responses in the shape Mastodon clients expect.
 */
@Component
class OAuthResponses {

    private static final String OUT_OF_BAND_REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob";

    private final JsonMapper json;
    private final ITemplateEngine templates;
    private final RedirectStrategy redirects = new DefaultRedirectStrategy();

    OAuthResponses(JsonMapper json, ITemplateEngine templates) {
        this.json = json;
        this.templates = templates;
    }

    /**
     * Sends an authorization code to the client. Spring's own handler can't do this for URIs with no authority, because
     * it silently drops query parameters on them. That also covers the out-of-band URI.
     */
    void authorizationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        var result = (OAuth2AuthorizationCodeRequestAuthenticationToken) authentication;
        var redirectUri = result.getRedirectUri();
        var code = result.getAuthorizationCode().getTokenValue();
        if (OUT_OF_BAND_REDIRECT_URI.equals(redirectUri)) {
            renderOutOfBand(response, HttpStatus.OK, Map.of("code", code));
            return;
        }

        var parameters = new LinkedHashMap<String, String>();
        parameters.put("code", code);
        putIfPresent(parameters, "state", result.getState());
        redirects.sendRedirect(request, response, withQuery(redirectUri, parameters));
    }

    /**
     * Reports an authorization failure. A client only gets a redirect once Spring has validated its redirect URI, which
     * is what leaving the URI on the exception's token signifies. Anything else must not redirect.
     */
    void authorizationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException {
        var error = ((OAuth2AuthenticationException) failure).getError();
        var result = failure instanceof OAuth2AuthorizationCodeRequestAuthenticationException requestFailure
                ? requestFailure.getAuthorizationCodeRequestAuthentication()
                : null;
        if (result == null || !StringUtils.hasText(result.getRedirectUri())) {
            response.sendError(HttpStatus.BAD_REQUEST.value(), error.getErrorCode());
            return;
        }

        var redirectUri = result.getRedirectUri();
        if (OUT_OF_BAND_REDIRECT_URI.equals(redirectUri)) {
            renderOutOfBand(response, HttpStatus.BAD_REQUEST, Map.of("error", error.getErrorCode()));
            return;
        }

        var parameters = new LinkedHashMap<String, String>();
        parameters.put("error", error.getErrorCode());
        putIfPresent(parameters, "error_description", error.getDescription());
        putIfPresent(parameters, "error_uri", error.getUri());
        putIfPresent(parameters, "state", result.getState());
        redirects.sendRedirect(request, response, withQuery(redirectUri, parameters));
    }

    /**
     * Writes Mastodon's Token entity, which leaves out {@code expires_in}.
     */
    void accessToken(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        OAuth2AccessToken token = ((OAuth2AccessTokenAuthenticationToken) authentication).getAccessToken();
        var body = new LinkedHashMap<String, Object>();
        body.put("access_token", token.getTokenValue());
        body.put("token_type", token.getTokenType().getValue());
        body.put("scope", String.join(" ", new TreeSet<>(token.getScopes())));
        body.put("created_at", (token.getIssuedAt() == null ? Instant.now() : token.getIssuedAt()).getEpochSecond());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        writeJson(response, HttpStatus.OK, body);
    }

    void revocationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        writeJson(response, HttpStatus.OK, Map.of());
    }

    /**
     * Client authentication failures don't reach this, so {@code invalid_client} here means the client authenticated
     * but the token belongs to another client.
     */
    void revocationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException {
        var error = ((OAuth2AuthenticationException) failure).getError();
        if (OAuth2ErrorCodes.INVALID_CLIENT.equals(error.getErrorCode())) {
            writeError(response, HttpStatus.FORBIDDEN, new OAuth2Error(
                    OAuth2ErrorCodes.UNAUTHORIZED_CLIENT, "You are not authorized to revoke this token", null));
            return;
        }
        writeError(response, HttpStatus.BAD_REQUEST, error);
    }

    void clientAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException {
        var code = failure instanceof OAuth2AuthenticationException oauth
                ? oauth.getError().getErrorCode()
                : OAuth2ErrorCodes.INVALID_CLIENT;
        // PKCE is checked during client authentication too, so this can also be invalid_grant or invalid_request.
        if (OAuth2ErrorCodes.INVALID_CLIENT.equals(code)) {
            writeError(response, HttpStatus.UNAUTHORIZED, new OAuth2Error(code,
                    "Client authentication failed due to unknown client, no client authentication included, or "
                            + "unsupported authentication method.", null));
            return;
        }
        writeError(response, HttpStatus.BAD_REQUEST, new OAuth2Error(code));
    }

    void invalidToken(HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        writeJson(response, HttpStatus.UNAUTHORIZED, Map.of("error", "The access token is invalid"));
    }

    void insufficientScope(HttpServletResponse response) throws IOException {
        writeJson(response, HttpStatus.FORBIDDEN, Map.of("error", "This action is outside the authorized scopes"));
    }

    private void writeError(HttpServletResponse response, HttpStatus status, OAuth2Error error) throws IOException {
        var body = new LinkedHashMap<String, Object>();
        body.put("error", error.getErrorCode());
        if (StringUtils.hasText(error.getDescription())) {
            body.put("error_description", error.getDescription());
        }
        writeJson(response, status, body);
    }

    private void writeJson(HttpServletResponse response, HttpStatus status, Map<String, ?> body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        response.getWriter().write(json.writeValueAsString(body));
    }

    private void renderOutOfBand(HttpServletResponse response, HttpStatus status, Map<String, Object> model)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        var context = new Context();
        context.setVariables(model);
        templates.process("oob-code", context, response.getWriter());
    }

    private static void putIfPresent(Map<String, String> parameters, String name, String value) {
        if (StringUtils.hasText(value)) {
            parameters.put(name, value);
        }
    }

    private static String withQuery(String uri, Map<String, String> parameters) {
        var result = new StringBuilder(uri);
        var separator = uri.contains("?") ? '&' : '?';
        for (var parameter : parameters.entrySet()) {
            result.append(separator)
                    .append(parameter.getKey())
                    .append('=')
                    .append(UriUtils.encode(parameter.getValue(), StandardCharsets.UTF_8));
            separator = '&';
        }
        return result.toString();
    }
}
