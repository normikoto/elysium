package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.api.streaming.StreamConnection;
import dev.biribiri.elysium.api.streaming.StreamKey;
import dev.biribiri.elysium.api.streaming.StreamRegistry;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Mastodon's streams as server-sent events, one stream for each request. The WebSocket at the same base path is set up
 * in {@code api.streaming}, and {@link StreamingApiDocs} describes it.
 */
@RestController
@RequestMapping("/api/v1/streaming")
@Tag(name = "Streaming", description = "Hearing about new statuses and notifications as they happen.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "streaming/"))
class StreamingController {

    static final String EVENTS = """
            Each event is sent as it happens, with Mastodon's names and payloads: `update` and `status.update` with a \
            Status, `delete` with a status ID, `notification` with a Notification, `conversation` with a \
            Conversation, and `filters_changed` with none, on `user` only. A status that the viewer's filters hide in \
            the timeline's context isn't sent. A comment is sent every 30 seconds so that the connection doesn't look \
            idle. Events can arrive out of order, or more than once.""";

    static final String DIFFERENCES = """
            Differences from Mastodon: a stream only carries what its timeline would list at the time, so a status \
            from another server only arrives once it's been stored here. There are no `announcement` or \
            `notifications_merged` events. A `delete` goes to every stream that carries statuses, whether or not it \
            carried that status. The connection closes once its token is revoked or expires, within about 30 \
            seconds. """;

    private static final String TOKEN = """
            The token can also be given as the `access_token` query parameter, since a browser's `EventSource` can't \
            set headers.""";

    private static final String OPEN = "The stream, which stays open until either side closes it.";

    private static final String TOO_MANY = "As many streaming connections are open as the server allows.";

    private final StreamRegistry registry;

    StreamingController(StreamRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(path = "/health", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(operationId = "getStreamingHealth", summary = "Check that streaming is available",
            description = "Answers `OK`, as Mastodon does.",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "streaming/#health"),
            responses = @ApiResponse(responseCode = "200", description = "Streaming is available.",
                    content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE, schema = @Schema(example = "OK"))))
    String health() {
        return "OK";
    }

    @GetMapping(path = "/user", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_read:statuses')")
    @Operation(operationId = "streamUser", summary = "Stream the home timeline and notifications",
            description = DIFFERENCES + "\n\nCarries what the home timeline would list, and the user's notifications "
                    + "if the token has `read:notifications`. " + EVENTS + " " + TOKEN + "\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "streaming/#user"),
            responses = {
                    @ApiResponse(responseCode = "200", description = OPEN),
                    @ApiResponse(responseCode = "422", description = "The token has no user."),
                    @ApiResponse(responseCode = "503", description = TOO_MANY)
            })
    ResponseEntity<SseEmitter> user(Authentication authentication) {
        return stream(authentication, StreamKey.parse("user", null));
    }

    @GetMapping(path = "/user/notification", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_read:notifications')")
    @Operation(operationId = "streamNotifications", summary = "Stream notifications",
            description = DIFFERENCES + "\n\n" + EVENTS + " " + TOKEN + "\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "streaming/#notification"),
            responses = {
                    @ApiResponse(responseCode = "200", description = OPEN),
                    @ApiResponse(responseCode = "422", description = "The token has no user."),
                    @ApiResponse(responseCode = "503", description = TOO_MANY)
            })
    ResponseEntity<SseEmitter> notifications(Authentication authentication) {
        return stream(authentication, StreamKey.parse("user:notification", null));
    }

    @GetMapping(path = "/direct", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_read:statuses')")
    @Operation(operationId = "streamDirect", summary = "Stream direct conversations",
            description = DIFFERENCES + "\n\nSends a `conversation` event whenever a status is added to one of the "
                    + "user's conversations. " + EVENTS + " " + TOKEN + "\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "streaming/#direct"),
            responses = {
                    @ApiResponse(responseCode = "200", description = OPEN),
                    @ApiResponse(responseCode = "422", description = "The token has no user."),
                    @ApiResponse(responseCode = "503", description = TOO_MANY)
            })
    ResponseEntity<SseEmitter> direct(Authentication authentication) {
        return stream(authentication, StreamKey.parse("direct", null));
    }

    @GetMapping(path = "/public", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:statuses')")
    @Operation(operationId = "streamPublic", summary = "Stream public statuses",
            description = DIFFERENCES + "Works without a token, like the public timeline.\n\n" + EVENTS + " " + TOKEN,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "streaming/#public"),
            parameters = @Parameter(name = "only_media", description = "Only stream statuses with media. "
                    + ApiDocs.FLAG),
            responses = {
                    @ApiResponse(responseCode = "200", description = OPEN),
                    @ApiResponse(responseCode = "503", description = TOO_MANY)
            })
    ResponseEntity<SseEmitter> publicStream(
            Authentication authentication, @RequestParam(name = "only_media", required = false) String onlyMedia) {
        return stream(authentication, StreamKey.parse(media("public", onlyMedia), null));
    }

    @GetMapping(path = "/public/local", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:statuses')")
    @Operation(operationId = "streamPublicLocal", summary = "Stream public statuses from this server",
            description = DIFFERENCES + "Works without a token, like the public timeline.\n\n" + EVENTS + " " + TOKEN,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "streaming/#public-local"),
            parameters = @Parameter(name = "only_media", description = "Only stream statuses with media. "
                    + ApiDocs.FLAG),
            responses = {
                    @ApiResponse(responseCode = "200", description = OPEN),
                    @ApiResponse(responseCode = "503", description = TOO_MANY)
            })
    ResponseEntity<SseEmitter> publicLocal(
            Authentication authentication, @RequestParam(name = "only_media", required = false) String onlyMedia) {
        return stream(authentication, StreamKey.parse(media("public:local", onlyMedia), null));
    }

    @GetMapping(path = "/public/remote", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:statuses')")
    @Operation(operationId = "streamPublicRemote", summary = "Stream public statuses from other servers",
            description = DIFFERENCES + "Works without a token, like the public timeline.\n\n" + EVENTS + " " + TOKEN,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "streaming/#public-remote"),
            parameters = @Parameter(name = "only_media", description = "Only stream statuses with media. "
                    + ApiDocs.FLAG),
            responses = {
                    @ApiResponse(responseCode = "200", description = OPEN),
                    @ApiResponse(responseCode = "503", description = TOO_MANY)
            })
    ResponseEntity<SseEmitter> publicRemote(
            Authentication authentication, @RequestParam(name = "only_media", required = false) String onlyMedia) {
        return stream(authentication, StreamKey.parse(media("public:remote", onlyMedia), null));
    }

    @GetMapping(path = "/hashtag", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:statuses')")
    @Operation(operationId = "streamHashtag", summary = "Stream public statuses with a hashtag",
            description = DIFFERENCES + "Works without a token, like the hashtag timeline.\n\n" + EVENTS + " " + TOKEN,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "streaming/#hashtag"),
            parameters = @Parameter(name = "tag", description = "The tag's name, without its `#`.", required = true),
            responses = {
                    @ApiResponse(responseCode = "200", description = OPEN),
                    @ApiResponse(responseCode = "400", description = "`tag` is missing, or isn't a valid tag name."),
                    @ApiResponse(responseCode = "503", description = TOO_MANY)
            })
    ResponseEntity<SseEmitter> hashtag(Authentication authentication, @RequestParam(required = false) String tag) {
        return stream(authentication, StreamKey.parse("hashtag", tag));
    }

    @GetMapping(path = "/hashtag/local", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:statuses')")
    @Operation(operationId = "streamHashtagLocal", summary = "Stream public statuses from this server with a hashtag",
            description = DIFFERENCES + "Works without a token, like the hashtag timeline.\n\n" + EVENTS + " " + TOKEN,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "streaming/#hashtag-local"),
            parameters = @Parameter(name = "tag", description = "The tag's name, without its `#`.", required = true),
            responses = {
                    @ApiResponse(responseCode = "200", description = OPEN),
                    @ApiResponse(responseCode = "400", description = "`tag` is missing, or isn't a valid tag name."),
                    @ApiResponse(responseCode = "503", description = TOO_MANY)
            })
    ResponseEntity<SseEmitter> hashtagLocal(Authentication authentication, @RequestParam(required = false) String tag) {
        return stream(authentication, StreamKey.parse("hashtag:local", tag));
    }

    // Each error names its content type, since an EventSource only accepts text/event-stream, which a JSON body
    // couldn't otherwise be written as.

    @ExceptionHandler
    ResponseEntity<Map<String, String>> invalidStream(StreamKey.InvalidStreamException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler
    ResponseEntity<Map<String, String>> requiresUser(InvalidRequestException e) {
        return error(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler
    ResponseEntity<Map<String, String>> tooManyConnections(StreamRegistry.TooManyConnectionsException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", message));
    }

    private ResponseEntity<SseEmitter> stream(Authentication authentication, StreamKey key) {
        // Never times out, since the heartbeat finds connections whose client has gone.
        var emitter = new SseEmitter(0L);
        var connection = registry.open(authentication, new Transport(emitter));
        var refusal = registry.subscribe(connection, key);
        if (refusal.isPresent()) {
            connection.closeNow();
            // Scopes were already checked, so the only refusal left is a token with no user.
            throw InvalidRequestException.requiresUser();
        }
        emitter.onCompletion(connection::closeNow);
        emitter.onTimeout(connection::closeNow);
        emitter.onError(_ -> connection.closeNow());
        // Something to send straight away, so that the client and any proxy see the stream has opened.
        connection.heartbeat();
        // nginx would otherwise hold events back until it had a buffer's worth.
        return ResponseEntity.ok().header("X-Accel-Buffering", "no").body(emitter);
    }

    private static String media(String stream, String onlyMedia) {
        return RequestParameters.flag(onlyMedia) ? stream + ":media" : stream;
    }

    private record Transport(SseEmitter emitter) implements StreamConnection.Transport {

        @Override
        public void event(String event, List<String> stream, String payload) throws IOException {
            emitter.send(SseEmitter.event().name(event).data(payload == null ? "" : payload, MediaType.TEXT_PLAIN));
        }

        @Override
        public void error(String message, int status) {
            // Only the WebSocket sends errors once it's open, since an event stream's errors are its status.
        }

        @Override
        public void heartbeat() throws IOException {
            emitter.send(SseEmitter.event().comment("thump"));
        }

        @Override
        public void close() {
            emitter.complete();
        }
    }
}
