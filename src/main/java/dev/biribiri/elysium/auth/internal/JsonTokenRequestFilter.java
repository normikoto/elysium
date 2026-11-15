package dev.biribiri.elysium.auth.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

/**
 * Lets token and revocation requests have a JSON body, as Mastodon's do. Apps descended from Mastodon's Android app send
 * one, and Spring only reads form parameters, so it would find no client credentials and answer {@code invalid_client}.
 * The body's fields become request parameters, which client authentication, the grants, and revocation all read.
 *
 * <p>Its limits and its fallback to parameters for an empty body match {@code api.web.JsonBodies}, which {@code auth}
 * can't use, so that a client sees the same behavior on every endpoint that takes JSON.
 *
 * <p>Not a bean, because Spring Boot would then also run it outside of the security filter chains.
 */
class JsonTokenRequestFilter extends OncePerRequestFilter {

    /**
     * Far more than a real request needs, whose longest field is a redirect URI of at most 2048 characters.
     */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private static final RequestMatcher TOKEN_REQUESTS = new OrRequestMatcher(
            pathPattern(HttpMethod.POST, SecurityConfig.TOKEN_PATH),
            pathPattern(HttpMethod.POST, SecurityConfig.REVOKE_PATH));

    /**
     * Refuses a repeated key, since a form field given twice is refused, and otherwise the last value would silently win,
     * such as a second {@code client_id} or {@code scope}.
     */
    private final ObjectReader json;
    private final OAuthResponses responses;

    JsonTokenRequestFilter(JsonMapper json, OAuthResponses responses) {
        this.json = json.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
        this.responses = responses;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !TOKEN_REQUESTS.matches(request) || jsonType(request.getContentType()) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            responses.bodyTooLarge(response);
            return;
        }
        var body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            responses.bodyTooLarge(response);
            return;
        }
        if (body.length == 0) {
            chain.doFilter(request, response);
            return;
        }
        var charset = jsonType(request.getContentType()).getCharset();
        JsonNode root;
        try {
            // Without a declared charset, Jackson detects the UTF encoding from the bytes.
            root = charset == null ? json.readTree(body) : json.readTree(new String(body, charset));
        } catch (JacksonException e) {
            root = null;
        }
        if (root == null || !root.isObject()) {
            responses.invalidRequest(response, "The request body must be a JSON object.");
            return;
        }
        chain.doFilter(new ParameterRequest(request, parameters(request, root)), response);
    }

    /**
     * Keeps the query string's parameters, which Spring tells apart from the body's. A field that's an array becomes a
     * parameter with several values, which Spring refuses as it would a repeated form field. Nested objects and nulls
     * are left out.
     */
    private static Map<String, String[]> parameters(HttpServletRequest request, JsonNode root) {
        var parameters = new LinkedHashMap<String, List<String>>();
        request.getParameterMap().forEach((name, values) -> parameters.put(name, new ArrayList<>(List.of(values))));
        for (var field : root.properties()) {
            var value = field.getValue();
            var values = value.isArray() ? value.values() : List.of(value);
            for (var element : values) {
                if (element.isValueNode() && !element.isNull()) {
                    parameters.computeIfAbsent(field.getKey(), _ -> new ArrayList<>()).add(element.asString());
                }
            }
        }
        var result = new LinkedHashMap<String, String[]>();
        parameters.forEach((name, values) -> result.put(name, values.toArray(String[]::new)));
        return Collections.unmodifiableMap(result);
    }

    /**
     * @return the content type if it's JSON, or else null. A wildcard such as {@code *}{@code /*} isn't JSON, so its
     * body is left to Spring.
     */
    private static MediaType jsonType(String contentType) {
        if (contentType == null) {
            return null;
        }
        try {
            var type = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(type) ? type : null;
        } catch (InvalidMediaTypeException e) {
            return null;
        }
    }

    private static final class ParameterRequest extends HttpServletRequestWrapper {

        private final Map<String, String[]> parameters;

        ParameterRequest(HttpServletRequest request, Map<String, String[]> parameters) {
            super(request);
            this.parameters = parameters;
        }

        @Override
        public String getParameter(String name) {
            var values = parameters.get(name);
            return values == null ? null : values[0];
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return parameters;
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(parameters.keySet());
        }

        @Override
        public String[] getParameterValues(String name) {
            var values = parameters.get(name);
            return values == null ? null : values.clone();
        }
    }
}
