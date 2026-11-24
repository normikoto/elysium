package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.auth.AppSignUps;
import dev.biribiri.elysium.auth.InvalidSignUpException;
import dev.biribiri.elysium.auth.SignUpLimitException;
import dev.biribiri.elysium.auth.SignUpRefusedException;
import dev.biribiri.elysium.auth.SignUpUnavailableException;
import dev.biribiri.elysium.auth.TokenPrincipal;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * Mastodon's {@code POST /api/v1/accounts}, which apps use to sign people up without sending them to the sign-up page.
 */
@RestController
@Tag(name = "Accounts")
class SignUpController {

    private static final String PATH = "/api/v1/accounts";

    /**
     * Room for the longest password and reason, and the other fields.
     */
    private static final long MAX_BODY_BYTES = 16 * 1024;

    private final AppSignUps signUps;
    private final JsonBodies json;

    SignUpController(AppSignUps signUps, JsonBodies json) {
        this.signUps = signUps;
        this.json = json;
    }

    @PostMapping(path = PATH, consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_write:accounts')")
    TokenResponse signUpFromForm(
            @AuthenticationPrincipal TokenPrincipal token,
            @RequestParam MultiValueMap<String, String> form,
            HttpServletRequest request) {
        return signUp(token, form.toSingleValueMap(), request);
    }

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_write:accounts')")
    // The API docs combine both mappings into one operation, and this replaces what the other one documents.
    @Operation(
            operationId = "registerAccount",
            summary = "Sign up for an account",
            description = """
                    Signs someone up, and returns a token for the new account, with the application's scopes. Any \
                    token works, including one from the `client_credentials` grant. The sign-up page's limit on \
                    sign-ups from one address applies. Requests that get 422 for a missing or malformed field don't \
                    count, but ones refused for a taken username or address, or an invite that doesn't work, do.

                    Differences from Mastodon:

                    - With `invite_code`, the account is created straight away, and `email` isn't needed or kept.
                    - Without one, the token gets 403 with "Your login is missing a confirmed e-mail address" until \
                    the emailed link is opened and confirmed, and then with "Your login is currently pending approval" \
                    until an admin approves the account, if the server asks for that. If the link expires or the \
                    sign-up is rejected, the token stops working, and gets 401.
                    - The token never has admin scopes.
                    - `locale`, `time_zone`, and `date_of_birth` are ignored. Emails are in English.
                    - Passwords can be up to 128 characters.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#create"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The new account's token."),
                    @ApiResponse(responseCode = "403", description = """
                            The token has none of the scopes listed, sign-ups need an invite, or the server's first \
                            account hasn't been created yet."""),
                    @ApiResponse(responseCode = "422", description = """
                            A field is missing or invalid, the username or email address is taken, `agreement` isn't \
                            true, or the invite doesn't work. `details` says which, by field, with Mastodon's codes."""),
                    @ApiResponse(responseCode = "429", description = "Too many sign-ups from this address.",
                            headers = @Header(name = "Retry-After", description = "Seconds before retrying.",
                                    schema = @Schema(type = "integer"))),
                    @ApiResponse(responseCode = "503", description = """
                            The server is too busy, or couldn't send the email to confirm the address.""")
            })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = SignUpBody.class)))
    TokenResponse signUpFromJson(@AuthenticationPrincipal TokenPrincipal token, HttpServletRequest request)
            throws IOException {
        return signUp(token, json.read(request, MAX_BODY_BYTES), request);
    }

    private TokenResponse signUp(TokenPrincipal token, Map<String, ?> parameters, HttpServletRequest request) {
        var signedUp = signUps.signUp(request, token.applicationId(), new AppSignUps.Form(
                RequestParameters.string(parameters, "username"),
                RequestParameters.string(parameters, "email"),
                RequestParameters.string(parameters, "password"),
                RequestParameters.flag(parameters, "agreement"),
                RequestParameters.string(parameters, "reason"),
                RequestParameters.string(parameters, "invite_code")));
        return new TokenResponse(
                signedUp.accessToken(),
                "Bearer",
                String.join(" ", signedUp.scopes()),
                signedUp.createdAt().getEpochSecond());
    }

    @ExceptionHandler
    ResponseEntity<Map<String, Object>> invalid(InvalidSignUpException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(Map.of("error", e.getMessage(), "details", e.details()));
    }

    @ExceptionHandler
    ResponseEntity<Map<String, String>> refused(SignUpRefusedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler
    ResponseEntity<Map<String, String>> tooMany(SignUpLimitException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, e.retryAfterSeconds())
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler
    ResponseEntity<Map<String, String>> unavailable(SignUpUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }

    /**
     * Mastodon's Token entity, as {@code /oauth/token} returns it.
     */
    @Schema(name = "Token")
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @Schema(description = "The token's scopes, separated by spaces.") String scope,
            @Schema(description = "When the token was issued, in seconds since the epoch.")
            @JsonProperty("created_at") long createdAt) {
    }

    /**
     * Only documents the body.
     */
    @Schema(name = "NewAccount")
    private record SignUpBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "1 to 30 letters, digits, or underscores.")
            String username,
            @Schema(description = "Required without `invite_code`, which is where the link to confirm it is sent.")
            String email,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "8 to 128 characters.")
            String password,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Whether the user agrees to the server's rules. Has to be true. " + ApiDocs.FLAG)
            Boolean agreement,
            @Schema(description = "Why they want to join, for the admins who approve sign-ups. Up to 1000 characters.")
            String reason,
            @Schema(description = "An invite, which creates the account straight away.")
            String invite_code,
            @Schema(description = "Ignored.") String locale,
            @Schema(description = "Ignored.") String time_zone,
            @Schema(description = "Ignored.") String date_of_birth) {
    }
}
