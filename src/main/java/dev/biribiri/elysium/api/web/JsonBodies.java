package dev.biribiri.elysium.api.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;

/**
 * Reads JSON request bodies by hand rather than through {@code @RequestBody}, which Spring would read in full before the
 * authorization check runs, however large it is. Tomcat already limits the size of form bodies.
 */
@Component
class JsonBodies {

    private final JsonMapper json;

    JsonBodies(JsonMapper json) {
        this.json = json;
    }

    /**
     * @param limit the most bytes to accept
     * @throws RequestTooLargeException if the body is over the limit
     * @throws InvalidRequestException  if the body isn't a JSON object
     */
    Map<String, Object> read(HttpServletRequest request, long limit) throws IOException {
        var bytes = request.getInputStream().readNBytes(Math.toIntExact(Math.min(limit + 1, Integer.MAX_VALUE - 8)));
        if (bytes.length > limit) {
            throw new RequestTooLargeException();
        }
        Map<String, Object> body;
        try {
            body = json.readValue(bytes, new TypeReference<>() {
            });
        } catch (JacksonException e) {
            body = null;
        }
        if (body == null) {
            throw new InvalidRequestException("Validation failed: the request body must be a JSON object");
        }
        return body;
    }
}
