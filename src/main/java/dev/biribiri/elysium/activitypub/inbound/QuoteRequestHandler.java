package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.vocab.QuoteAuthorization;
import dev.biribiri.elysium.activitypub.vocab.QuoteRequestAnswer;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.QuoteDecision;
import dev.biribiri.elysium.content.QuoteService;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * Answers another server's request to approve a quote of a local status, as FEP-044f has it. The status's policy decides,
 * automatically, as on Mastodon: an {@code Accept} carries a {@code QuoteAuthorization} that this server serves, and a
 * {@code Reject} stores nothing. Only a public or unlisted status can be quoted by anyone but its author.
 *
 * <p>The request's {@code instrument} is the quote, which is stored, since the quoted author has to see it, and the quote's
 * server may never send it otherwise. It's checked as a {@code Create}'s note is: it has to be the actor's, on the actor's
 * server, and it has to quote the status asked about.
 */
@Component
class QuoteRequestHandler implements InboundActivityHandler {

    private static final Logger log = LoggerFactory.getLogger(QuoteRequestHandler.class);

    private final ActorNotes notes;
    private final NoteStorage storage;
    private final RemoteActors remoteActors;
    private final ActorService actors;
    private final StatusService statuses;
    private final BoostService boosts;
    private final QuoteService quotes;
    private final DomainBlocks blocks;
    private final ActivityDeliveries deliveries;
    private final TransactionTemplate transaction;
    private final JsonMapper json;

    QuoteRequestHandler(
            ActorNotes notes,
            NoteStorage storage,
            RemoteActors remoteActors,
            ActorService actors,
            StatusService statuses,
            BoostService boosts,
            QuoteService quotes,
            DomainBlocks blocks,
            ActivityDeliveries deliveries,
            PlatformTransactionManager transactionManager,
            JsonMapper json) {
        this.notes = notes;
        this.storage = storage;
        this.remoteActors = remoteActors;
        this.actors = actors;
        this.statuses = statuses;
        this.boosts = boosts;
        this.quotes = quotes;
        this.blocks = blocks;
        this.deliveries = deliveries;
        this.transaction = new TransactionTemplate(transactionManager);
        this.json = json;
    }

    @Override
    public String type() {
        return "QuoteRequest";
    }

    @Override
    public void handle(InboundActivity activity) {
        var document = json.readTree(activity.body());
        var quotedUri = Optional.ofNullable(ActivityJson.idOf(document.path("object"))).flatMap(ActivityObject::parseUri);
        var quoted = quotedUri.flatMap(boosts::localTarget)
                .filter(status -> status.visibility() == Visibility.PUBLIC || status.visibility() == Visibility.UNLISTED);
        var quotedAuthor = quoted.flatMap(status -> actors.findLocalById(status.actorId()));
        if (quoted.isEmpty() || quotedAuthor.isEmpty()) {
            log.debug("Ignoring QuoteRequest {}, which isn't of a local status that can be quoted", activity.id());
            return;
        }
        var read = notes.read(activity, document, "instrument");
        if (read.isEmpty() || read.get().note().quote() == null
                || !read.get().note().quote().quotedUri().equals(quoted.get().uri())) {
            log.debug("Ignoring QuoteRequest {}, whose instrument isn't its actor's quote of the status", activity.id());
            return;
        }
        var requester = remoteActors.fetchOrRefresh(URI.create(activity.actorUri()));
        var quote = read.get().note().id();
        if (quotes.decide(quoted.get(), requester.id()) != QuoteDecision.AUTOMATIC) {
            reject(activity, quotedAuthor.get(), requester, quoted.get(), quote);
            return;
        }
        var quoting = statuses.findStoredByUri(quote, requester.id());
        if (quoting.isEmpty() && storage.storeInstrument(activity, read.get(), quotedAuthor.get().id())
                == NoteStorage.Result.IGNORED) {
            reject(activity, quotedAuthor.get(), requester, quoted.get(), quote);
            return;
        }
        var stamp = statuses.findStoredByUri(quote, requester.id())
                .flatMap(status -> quotes.approveRequest(status.id(), quoted.get().id(), URI.create(activity.id())));
        if (stamp.isEmpty()) {
            reject(activity, quotedAuthor.get(), requester, quoted.get(), quote);
            return;
        }
        var accept = QuoteRequestAnswer.accept(quotedAuthor.get().uri(), activity.id(), requester.uri(),
                quoted.get().uri(), quote, QuoteAuthorization.idFor(quotedAuthor.get().uri(), stamp.get()));
        send(activity, quotedAuthor.get(), requester, accept);
    }

    private void reject(InboundActivity activity, Actor quotedAuthor, Actor requester, Status quoted, URI quote) {
        log.debug("Rejecting QuoteRequest {}", activity.id());
        send(activity, quotedAuthor, requester,
                QuoteRequestAnswer.reject(quotedAuthor.uri(), activity.id(), requester.uri(), quoted.uri(), quote));
    }

    /**
     * By arrival, so that a repeat of the request gets an answer, while this running again doesn't send another.
     */
    private void send(InboundActivity activity, Actor quotedAuthor, Actor requester, QuoteRequestAnswer answer) {
        transaction.executeWithoutResult(tx -> {
            // Fetching can take a while, and a block or suspension can have landed since the job started.
            if (blocks.isBlocked(requester.uri().getHost()) || actors.isInactive(requester.uri())) {
                return;
            }
            deliveries.enqueue(
                    new OutboundActivity(answer.id().toString(), quotedAuthor.id(), json.writeValueAsString(answer)),
                    List.of(requester.inboxUri()),
                    activity.receivedAt().toString());
        });
    }

    /**
     * Answering is the same each time, and a server that hasn't had the answer may ask again.
     */
    @Override
    public boolean processesRepeats() {
        return true;
    }
}
