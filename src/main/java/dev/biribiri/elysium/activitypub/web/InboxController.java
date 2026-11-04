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
 * <p>The only thing limited here is the key fetches that an address can cause, through {@link AddressFetchBudgets}. Once a
 * server's keys are cached, it can deliver as much as it wants to.
 */
@RestController
class InboxController {

    private static final Logger log = LoggerFactory.getLogger(InboxController.class);

    private static final int MAX_BODY_BYTES = 1024 * 1024;

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
    ResponseEntity<Void> personalInbox(@PathVariable String username, HttpServletRequest request) throws IOException {
        if (actors.findLocalByUsername(username).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return receive(request);
    }

    @PostMapping(path = {"/inbox", InstanceActor.PATH + "/inbox"}, consumes = {ACTIVITY_JSON, ANY_LD_JSON})
    ResponseEntity<Void> sharedOrInstanceActorInbox(HttpServletRequest request) throws IOException {
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
            return switch (inbox.receive(signer, body)) {
                case ACCEPTED, IGNORED -> ResponseEntity.accepted().build();
                case INVALID -> ResponseEntity.badRequest().build();
                case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).build();
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
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return null;
        }
        var body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        return body.length > MAX_BODY_BYTES ? null : body;
    }
}
