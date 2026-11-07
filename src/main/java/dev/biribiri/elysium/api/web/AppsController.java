package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.auth.ClientRegistrations;
import dev.biribiri.elysium.auth.InvalidClientRegistrationException;
import dev.biribiri.elysium.auth.TokenPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/v1/apps")
class AppsController {

    private final ClientRegistrations registrations;

    AppsController(ClientRegistrations registrations) {
        this.registrations = registrations;
    }

    @PostMapping(consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    ApplicationResponse registerFromForm(@RequestParam MultiValueMap<String, String> form) {
        var redirectUris = new ArrayList<String>();
        addAll(redirectUris, form.get("redirect_uris"));
        addAll(redirectUris, form.get("redirect_uris[]"));
        return ApplicationResponse.withCredentials(registrations.register(
                form.getFirst("client_name"),
                splitRedirectUris(redirectUris),
                form.getFirst("scopes"),
                form.getFirst("website")));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ApplicationResponse registerFromJson(@RequestBody Map<String, Object> body) {
        var redirectUris = new ArrayList<String>();
        switch (body.get("redirect_uris")) {
            case String uris -> redirectUris.add(uris);
            case List<?> uris -> uris.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .forEach(redirectUris::add);
            case null, default -> {
            }
        }
        return ApplicationResponse.withCredentials(registrations.register(
                string(body.get("client_name")),
                splitRedirectUris(redirectUris),
                string(body.get("scopes")),
                string(body.get("website"))));
    }

    /**
     * Any token works, with any scope, so there's no {@code @PreAuthorize} to document this from.
     */
    @GetMapping("/verify_credentials")
    @SecurityRequirement(name = "oauth2")
    ResponseEntity<ApplicationResponse> verifyCredentials(@AuthenticationPrincipal TokenPrincipal token) {
        return ResponseEntity.of(registrations.findById(token.applicationId()).map(ApplicationResponse::from));
    }

    @ExceptionHandler
    ResponseEntity<Map<String, String>> invalidRegistration(InvalidClientRegistrationException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(Map.of("error", "Validation failed: " + e.getMessage()));
    }

    /**
     * Older clients send several redirect URIs as a single value separated by whitespace.
     */
    private static List<String> splitRedirectUris(List<String> values) {
        return values.stream()
                .flatMap(value -> Arrays.stream(value.trim().split("\\s+")))
                .flatMap(value -> value.isEmpty() ? Stream.empty() : Stream.of(value))
                .distinct()
                .toList();
    }

    private static void addAll(List<String> target, List<String> values) {
        if (values != null) {
            target.addAll(values);
        }
    }

    private static String string(Object value) {
        return value instanceof String string ? string : null;
    }
}
