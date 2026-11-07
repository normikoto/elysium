package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.inbound.InboundActivity;
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
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
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
// What every inbox answers. FederationApiDocs describes the request.
@ApiResponse(responseCode = "202", description = """
        The activity is queued, repeats one with the same `id`, was forwarded and will be fetched from its origin, or was \
        dropped. A stored actor's `Delete` of itself whose key is gone is also answered this way, and checked with its \
        server.""")
@ApiResponse(responseCode = "400", description = """
        The signature header can't be read, the body isn't a UTF-8 JSON object with a string `id`, `type`, and `actor`, \
        or the `id` isn't a valid URI or is over\s""" + InboundActivity.MAX_ID_BYTES + " bytes.")
@ApiResponse(responseCode = "401", description = "There's no signature in a supported format, or it isn't valid.")
@ApiResponse(responseCode = "403", description = """
        The `id` isn't an HTTPS URL on the same host and port as the `actor`, the signer is suspended or deleted, or the \
        signature's key is on a blocked domain.""")
@ApiResponse(responseCode = "413", description = "The body is over " + Inbox.MAX_BODY_BYTES / (1024 * 1024) + " MiB.")
@ApiResponse(responseCode = "415", description = "The `Content-Type` isn't one of those accepted.")
@ApiResponse(responseCode = "429", description = """
        This address has caused too many key fetches, or the signer's server has delivered too much.""",
        headers = @Header(name = "Retry-After", description = "Seconds until there's room again.",
                schema = @Schema(type = "integer")))
@ApiResponse(responseCode = "503", description = "The sender's key can't be fetched right now.")
class InboxController {

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

    @PostMapping(path = "/users/{username}/inbox", consumes = {ACTIVITY_JSON, ANY_LD_JSON})
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

    @PostMapping(path = "/inbox", consumes = {ACTIVITY_JSON, ANY_LD_JSON})
    @Operation(
            operationId = "postToSharedInbox",
            summary = "Deliver an activity to the shared inbox",
            description = "Every actor document gives this as its `endpoints.sharedInbox`. " + FederationDocs.INBOX)
    ResponseEntity<Void> sharedInbox(HttpServletRequest request) throws IOException {
        return receive(request);
    }

    @PostMapping(path = InstanceActor.PATH + "/inbox", consumes = {ACTIVITY_JSON, ANY_LD_JSON})
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
            return respond(inbox.receive(signer, body));
        } catch (SignatureVerificationException e) {
            var unverified = e.keyDocument().flatMap(actor -> inbox.receiveUnverifiedDelete(actor, body));
            if (unverified.isPresent()) {
                return respond(unverified.get());
            }
            log.debug("Refusing a delivery: {}", e.getMessage());
            var response = ResponseEntity.status(e.failure().status());
            if (e.failure() == Failure.FETCH_BUDGET_SPENT) {
                response.header(
                        HttpHeaders.RETRY_AFTER, RateLimiter.retryAfterSeconds(budgets.timeUntilReset(address)));
            }
            return response.build();
        }
    }

    private static ResponseEntity<Void> respond(Inbox.Result result) {
        return switch (result.outcome()) {
            case ACCEPTED, FORWARDED, IGNORED -> ResponseEntity.accepted().build();
            case INVALID -> ResponseEntity.badRequest().build();
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, RateLimiter.retryAfterSeconds(result.retryAfter()))
                    .build();
        };
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
