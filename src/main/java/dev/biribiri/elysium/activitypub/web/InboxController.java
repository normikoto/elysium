package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.inbound.Inbox;
import dev.biribiri.elysium.activitypub.signature.HttpRequestData;
import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException;
import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException.Failure;
import dev.biribiri.elysium.activitypub.signature.SignatureVerifier;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceActor;
import dev.biribiri.elysium.ratelimit.ClientAddresses;
import dev.biribiri.elysium.ratelimit.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.ACTIVITY_JSON;
import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.ANY_LD_JSON;

/**
 * Receives activities from other servers. All inboxes do the same thing, because who an activity is for comes from the
 * activity rather than the URL it was sent to.
 *
 * <p>Two things are limited: the key fetches that an address can cause, through {@link AddressFetchBudgets}, and the
 * activities each server can have stored, which {@link Inbox} checks. Requests that are unsigned or fail their signature
 * aren't limited, which is left to a reverse proxy.
 */
@RestController
@Tag(name = FederationDocs.ACTIVITYPUB_TAG)
class InboxController {

    static final String PERSONAL_INBOX_PATH = "/users/{username}/inbox";
    static final String SHARED_INBOX_PATH = "/inbox";
    static final String INSTANCE_ACTOR_INBOX_PATH = InstanceActor.PATH + "/inbox";

    private static final Logger log = LoggerFactory.getLogger(InboxController.class);

    private final ActorService actors;
    private final Inbox inbox;
    private final SignatureVerifier verifier;
    private final ClientAddresses clients;
    private final AddressFetchBudgets budgets;

    InboxController(
            ActorService actors,
            Inbox inbox,
            SignatureVerifier verifier,
            ClientAddresses clients,
            AddressFetchBudgets budgets) {
        this.actors = actors;
        this.inbox = inbox;
        this.verifier = verifier;
        this.clients = clients;
        this.budgets = budgets;
    }

    @PostMapping(path = PERSONAL_INBOX_PATH, consumes = {ACTIVITY_JSON, ANY_LD_JSON})
    // FederationApiDocs adds what every inbox has in common.
    @Operation(
            operationId = "postToUserInbox",
            summary = "Deliver an activity to a user's inbox",
            description = FederationDocs.INBOX
                    + "\n\nA suspended user's inbox still accepts activities, such as an `Undo` of a `Follow`.",
            parameters = @Parameter(name = "username", description = FederationDocs.USERNAME),
            responses = {
                    @ApiResponse(responseCode = "404", description = "There's no such user."),
                    @ApiResponse(responseCode = "410", description = "The user was deleted.")
            })
    ResponseEntity<Void> personalInbox(@PathVariable String username, HttpServletRequest request) throws IOException {
        // A suspended actor can still be sent things, such as an Undo of a Follow, or it could lose followers it should keep.
        var actor = actors.findAnyLocalByUsername(username);
        if (actor.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (actor.get().isDeleted()) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }
        return receive(request);
    }

    @PostMapping(path = SHARED_INBOX_PATH, consumes = {ACTIVITY_JSON, ANY_LD_JSON})
    @Operation(
            operationId = "postToSharedInbox",
            summary = "Deliver an activity to the shared inbox",
            description = "Every actor document gives this as its `endpoints.sharedInbox`. " + FederationDocs.INBOX)
    ResponseEntity<Void> sharedInbox(HttpServletRequest request) throws IOException {
        return receive(request);
    }

    @PostMapping(path = INSTANCE_ACTOR_INBOX_PATH, consumes = {ACTIVITY_JSON, ANY_LD_JSON})
    @Operation(
            operationId = "postToInstanceActorInbox",
            summary = "Deliver an activity to the instance actor's inbox",
            description = FederationDocs.INBOX)
    ResponseEntity<Void> instanceActorInbox(HttpServletRequest request) throws IOException {
        return receive(request);
    }

    private ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
        var body = readBody(request);
        if (body == null) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }

        var address = clients.keyFor(request);
        // Called with no transaction open, because this can wait on another server.
        var data = HttpRequestData.from(request, body);
        try {
            var signer = verifier.verify(data, budgets.forAddress(address));
            var result = inbox.receive(signer, body);
            return switch (result.outcome()) {
                case ACCEPTED, FORWARDED, IGNORED -> ResponseEntity.accepted().build();
                case INVALID -> ResponseEntity.badRequest().build();
                case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, RateLimiter.retryAfterSeconds(result.retryAfter()))
                        .build();
            };
        } catch (SignatureVerificationException e) {
            log.debug("Refusing a delivery: {}", e.getMessage());
            var response = ResponseEntity.status(e.failure().status());
            if (e.failure() == Failure.FETCH_BUDGET_SPENT) {
                response.header(
                        HttpHeaders.RETRY_AFTER, RateLimiter.retryAfterSeconds(budgets.timeUntilReset(address)));
            }
            return response.build();
        }
    }

    /**
     * @return the body, or null if it's over the limit
     */
    private static byte[] readBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > Inbox.MAX_BODY_BYTES) {
            return null;
        }
        var body = request.getInputStream().readNBytes(Inbox.MAX_BODY_BYTES + 1);
        return body.length > Inbox.MAX_BODY_BYTES ? null : body;
    }
}
