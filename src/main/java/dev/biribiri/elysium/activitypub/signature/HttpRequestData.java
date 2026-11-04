package dev.biribiri.elysium.activitypub.signature;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

import java.util.Collections;

/**
 * A request as it was received, in a form that doesn't depend on the signature format.
 *
 * @param rawTarget the path and query exactly as they were sent, without decoding
 */
public record HttpRequestData(String method, String rawTarget, HttpHeaders headers, byte[] body) {

    /**
     * Reads the servlet's headers directly. Spring's request wrappers can add a charset to {@code Content-Type} and fill
     * in {@code Content-Length}, which would break signatures that cover those headers.
     *
     * @param body the request's body, which the caller has to read and limit the size of
     */
    public static HttpRequestData from(HttpServletRequest request, byte[] body) {
        var headers = new HttpHeaders();
        for (var name : Collections.list(request.getHeaderNames())) {
            for (var value : Collections.list(request.getHeaders(name))) {
                headers.add(name, value);
            }
        }
        var query = request.getQueryString();
        var target = query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
        return new HttpRequestData(request.getMethod(), target, headers, body);
    }
}
