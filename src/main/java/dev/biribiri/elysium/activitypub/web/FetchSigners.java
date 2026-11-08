package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.signature.HttpRequestData;
import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException;
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
 * <p>Signatures aren't required yet (#92), so one that can't be accepted for any reason, including a key that can't be
 * fetched right now, gets the answer an unsigned request gets. Servers that don't sign, or sign badly, see no change. For a
 * followers-only or direct status, that answer is a 404, which Mastodon and GoToSocial don't take as a deletion of one.
 *
 * <p>Key fetches are charged to the requesting address, as for inboxes, since anyone can name a key on any host.
 */
@Component
class FetchSigners {

    private static final Logger log = LoggerFactory.getLogger(FetchSigners.class);
    static final String SIGNATURE = "Signature";

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
        if (request.getHeader(SIGNATURE) == null) {
            return Optional.empty();
        }
        try {
            var signature = verifier.verify(
                    HttpRequestData.from(request, new byte[0]), budgets.forAddress(clients.keyFor(request)));
            // A block or suspension can have been added since the key was checked.
            if (blocks.isBlocked(signature.owner().getHost())) {
                return Optional.empty();
            }
            return actors.findByUri(signature.owner()).filter(Actor::isActive);
        } catch (SignatureVerificationException e) {
            log.debug("Answering a fetch of {} as unsigned: {}", request.getRequestURI(), e.getMessage());
            return Optional.empty();
        }
    }
}
