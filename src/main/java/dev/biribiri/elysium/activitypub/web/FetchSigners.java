package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.signature.HttpRequestData;
import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException;
import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException.Failure;
import dev.biribiri.elysium.activitypub.signature.SignatureVerifier;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import dev.biribiri.elysium.ratelimit.ClientAddresses;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Finds who signed a fetch of an ActivityPub document, so that it can be answered as that actor. Only the actor that owns
 * the key counts. An instance actor doesn't stand for the users on its server, which is also how Mastodon decides.
 *
 * <p>Unless signed fetches are required, a signature that can't be accepted for any reason, including a key that can't be
 * fetched right now, gets the answer an unsigned request gets, so servers that don't sign, or sign badly, see no change. For
 * a followers-only or direct status, that answer is a 404, which Mastodon and GoToSocial don't take as a deletion of one.
 * When they're required, {@link SignedFetchInterceptor} refuses the request instead.
 *
 * <p>Key fetches are charged to the requesting address, as for inboxes, since anyone can name a key on any host.
 */
@Component
class FetchSigners {

    private static final Logger log = LoggerFactory.getLogger(FetchSigners.class);
    static final String SIGNATURE = "Signature";
    private static final String CHECKED = FetchSigners.class.getName() + ".checked";

    private final SignatureVerifier verifier;
    private final AddressFetchBudgets budgets;
    private final ClientAddresses clients;
    private final DomainBlocks blocks;
    private final ActorService actors;

    FetchSigners(
            SignatureVerifier verifier,
            AddressFetchBudgets budgets,
            ClientAddresses clients,
            DomainBlocks blocks,
            ActorService actors) {
        this.verifier = verifier;
        this.budgets = budgets;
        this.clients = clients;
        this.blocks = blocks;
        this.actors = actors;
    }

    /**
     * This can block while fetching a key, so it mustn't be called inside a database transaction.
     *
     * @return the active actor that signed the request, or empty if it's unsigned or its signature can't be accepted
     */
    Optional<Actor> signer(HttpServletRequest request) {
        return check(request) instanceof FetchSignature.Signed(var actor) ? actor : Optional.empty();
    }

    /**
     * Checks the request's signature once, and gives the same answer if asked again for the same request, so that a key
     * fetch isn't charged twice. Like {@link #signer}, this can block.
     */
    FetchSignature check(HttpServletRequest request) {
        if (request.getAttribute(CHECKED) instanceof FetchSignature checked) {
            return checked;
        }
        var checked = verify(request);
        request.setAttribute(CHECKED, checked);
        return checked;
    }

    private FetchSignature verify(HttpServletRequest request) {
        if (request.getHeader(SIGNATURE) == null) {
            return new FetchSignature.Unsigned();
        }
        try {
            var signature = verifier.verify(
                    HttpRequestData.from(request, new byte[0]), budgets.forAddress(clients.keyFor(request)));
            // A block or suspension can have been added since the key was checked.
            if (blocks.isBlocked(signature.owner().getHost())) {
                return refused(request, Failure.DOMAIN_BLOCKED, "key's server is blocked");
            }
            // Keys are kept apart from actors, so most signers, such as other servers' instance actors, aren't stored.
            var owner = actors.findByUri(signature.owner());
            if (owner.isPresent() && !owner.get().isActive()) {
                return refused(request, Failure.ACTOR_SUSPENDED, "signer is suspended or deleted");
            }
            return new FetchSignature.Signed(owner);
        } catch (SignatureVerificationException e) {
            return refused(request, e.failure(), e.getMessage());
        }
    }

    private static FetchSignature refused(HttpServletRequest request, Failure failure, String reason) {
        log.debug("Not accepting the signature on a fetch of {}: {}", request.getRequestURI(), reason);
        return new FetchSignature.Refused(failure);
    }

    sealed interface FetchSignature {

        record Unsigned() implements FetchSignature {
        }

        /**
         * @param actor the signer, which is active and not on a blocked server, or empty if it isn't stored here. Either
         *              way, the signature is valid.
         */
        record Signed(Optional<Actor> actor) implements FetchSignature {
        }

        record Refused(Failure failure) implements FetchSignature {
        }
    }
}
