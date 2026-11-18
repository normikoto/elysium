package dev.biribiri.elysium.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * Refuses a multipart request that's larger than its endpoint's {@link UploadLimits limit} before any of it is read.
 * Spring's own limit is the largest of every endpoint's, and it's only reached once a file has been written to disk.
 *
 * <p>A request has to declare its length, so one that's chunked is refused. Tomcat parses multipart bodies from its own
 * stream, which a filter can't wrap to cut short, while it never reads past a declared length.
 */
class UploadLengthFilter extends OncePerRequestFilter {

    private final UploadLimits limits;
    private final JsonMapper json;

    UploadLengthFilter(UploadLimits limits, JsonMapper json) {
        this.limits = limits;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var contentType = request.getContentType();
        return contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var length = request.getContentLengthLong();
        if (length < 0) {
            refuse(request, response, HttpStatus.LENGTH_REQUIRED, "Uploads need a Content-Length header");
        } else if (length > limits.limitFor(request)) {
            refuse(request, response, HttpStatus.CONTENT_TOO_LARGE, "Request body is too large");
        } else {
            chain.doFilter(request, response);
        }
    }

    /**
     * The API's errors have Mastodon's {@code Error} body, as {@code StatusApiErrors} gives them, and a page's are shown
     * on the error page.
     */
    private void refuse(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String error)
            throws IOException {
        if (request.getRequestURI().startsWith("/api/")) {
            response.setStatus(status.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(), Map.of("error", error));
        } else {
            response.sendError(status.value());
        }
    }
}
