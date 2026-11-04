package dev.biribiri.elysium.activitypub.web;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.biribiri.elysium.activitypub.inbound.Inbox;
import dev.biribiri.elysium.activitypub.signature.HttpRequestData;
import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException;
import dev.biribiri.elysium.activitypub.signature.SignatureVerifier;
import dev.biribiri.elysium.actor.ActorService;
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
import java.time.Duration;

/**
 * Receives activities from other servers. Both inboxes do the same thing, because who an activity is for comes from the
 * activity rather than the URL it was sent to.
 *
 * <p>Anyone can name a key on any host and make this server fetch it, and {@code PublicKeyResolver}'s limits are shared by
 * everyone sending here. So an address only gets a few failed signatures, which is what stops one sender from using up
 * those limits and getting 503s for real senders. A slot is taken before checking a signature rather than after it fails,
 * because a fetch from a slow host can take long enough for many more requests to arrive. Signatures that pass give their
 * slots back, so this doesn't limit how much a real server can deliver.
 */
@RestController
class InboxController {

    private static final Logger log = LoggerFactory.getLogger(InboxController.class);

    private static final String ACTIVITY_JSON = "application/activity+json";
    /** Matches any profile, since servers send variations of it. */
    private static final String LD_JSON = "application/ld+json";

    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final int MAX_FAILURES_PER_ADDRESS_PER_MINUTE = 30;
    private static final int MAX_TRACKED_ADDRESSES = 100_000;

    private final ActorService actors;
    private final Inbox inbox;
    private final SignatureVerifier verifier;
    private final ClientAddresses clients;
    private final RateLimiter failuresByAddress = new RateLimiter(
            MAX_FAILURES_PER_ADDRESS_PER_MINUTE, Duration.ofMinutes(1), MAX_TRACKED_ADDRESSES, Ticker.systemTicker());

    InboxController(ActorService actors, Inbox inbox, SignatureVerifier verifier, ClientAddresses clients) {
        this.actors = actors;
        this.inbox = inbox;
        this.verifier = verifier;
        this.clients = clients;
    }

    @PostMapping(path = "/users/{username}/inbox", consumes = {ACTIVITY_JSON, LD_JSON})
    ResponseEntity<Void> personalInbox(@PathVariable String username, HttpServletRequest request) throws IOException {
        if (actors.findLocalByUsername(username).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return receive(request);
    }

    @PostMapping(path = "/inbox", consumes = {ACTIVITY_JSON, LD_JSON})
    ResponseEntity<Void> sharedInbox(HttpServletRequest request) throws IOException {
        return receive(request);
    }

    private ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
        var address = clients.keyFor(request);
        var slot = failuresByAddress.tryAcquire(address);
        if (slot.isEmpty()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER,
                            RateLimiter.retryAfterSeconds(failuresByAddress.timeUntilReset(address)))
                    .build();
        }

        var body = readBody(request);
        if (body == null) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }

        // Called with no transaction open, because this can wait on another server.
        var data = HttpRequestData.from(request, body);
        try {
            var signer = verifier.verify(data);
            slot.get().release();
            return switch (inbox.receive(signer, body)) {
                case ACCEPTED, IGNORED -> ResponseEntity.accepted().build();
                case INVALID -> ResponseEntity.badRequest().build();
                case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            };
        } catch (SignatureVerificationException e) {
            log.debug("Refusing a delivery: {}", e.getMessage());
            return ResponseEntity.status(e.failure().status()).build();
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
