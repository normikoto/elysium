package dev.biribiri.elysium.auth.internal;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.util.matcher.RequestMatcher;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

/**
 * Reads the token from the {@code Authorization} header, and for streaming also from where Mastodon's clients put it when
 * they can't set headers: the {@code access_token} query parameter, which browsers' {@code EventSource} needs, and the
 * {@code Sec-WebSocket-Protocol} a browser's WebSocket can send. Nowhere else accepts those, since a token in a URL ends
 * up in logs and browser history.
 */
final class StreamingTokenResolver implements BearerTokenResolver {

    static final String STREAMING_PATH = "/api/v1/streaming";

    private static final RequestMatcher STREAMING = pathPattern(STREAMING_PATH + "/**");
    private static final RequestMatcher WEBSOCKET = pathPattern(STREAMING_PATH);

    private final DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        var token = header.resolve(request);
        if (token != null || !STREAMING.matches(request)) {
            return token;
        }
        var parameter = request.getParameter("access_token");
        if (parameter != null && !parameter.isBlank()) {
            return parameter;
        }
        if (WEBSOCKET.matches(request) && "websocket".equalsIgnoreCase(request.getHeader(HttpHeaders.UPGRADE))) {
            var protocols = request.getHeader("Sec-WebSocket-Protocol");
            if (protocols != null && !protocols.isBlank()) {
                return protocols.split(",", 2)[0].strip();
            }
        }
        return null;
    }
}
