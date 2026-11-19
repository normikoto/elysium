package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.vocab.QuoteAuthorization;
import dev.biribiri.elysium.activitypub.vocab.QuoteRequestAnswer;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.QuoteReviewed;
import dev.biribiri.elysium.content.QuoteRevoked;
import dev.biribiri.elysium.content.QuoteService;
import dev.biribiri.elysium.content.QuoteState;
import dev.biribiri.elysium.content.StatusQuote;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

/**
 * Answers another server's {@code QuoteRequest} once the quoted author has reviewed it by hand, with the same
 * {@code Accept} or {@code Reject} that answering it straight away would have sent. Only what the quote is now is sent,
 * so that this running again, or after the author changed their mind, sends nothing that's since been undone, and an
 * {@code Accept} goes after any {@code Reject} it replaces. A request whose quote is revoked while it waits, such as when
 * its author is blocked, gets a {@code Reject} too.
 */
@Service
public class QuoteReviewAnswers {

    private final QuoteService quotes;
    private final StatusService statuses;
    private final ActorService actors;
    private final DomainBlocks blocks;
    private final ActivityDeliveries deliveries;
    private final JsonMapper json;

    QuoteReviewAnswers(
            QuoteService quotes,
            StatusService statuses,
            ActorService actors,
            DomainBlocks blocks,
            ActivityDeliveries deliveries,
            JsonMapper json) {
        this.quotes = quotes;
        this.statuses = statuses;
        this.actors = actors;
        this.blocks = blocks;
        this.deliveries = deliveries;
        this.json = json;
    }

    @ApplicationModuleListener
    public void on(QuoteReviewed event) {
        var quote = quotes.find(event.statusId())
                .filter(found -> found.requestUri() != null && found.quotedStatusId() != null
                        && event.quotedAuthorId().equals(found.quotedAccountId()))
                .filter(found -> event.accepted()
                        ? found.state() == QuoteState.ACCEPTED && found.stampId() != null
                        : found.state() == QuoteState.REJECTED);
        if (quote.isEmpty()) {
            return;
        }
        answer(quote.get(), event.authorId(), event.quotedAuthorId(), event.accepted());
    }

    @ApplicationModuleListener
    public void on(QuoteRevoked event) {
        if (!event.unanswered()) {
            return;
        }
        // A quote that's been asked for again since has its own answer coming.
        quotes.find(event.statusId())
                .filter(found -> found.state() == QuoteState.REVOKED && found.requestUri() != null
                        && found.quotedStatusId() != null && event.quotedAuthorId().equals(found.quotedAccountId()))
                .ifPresent(found -> answer(found, event.authorId(), event.quotedAuthorId(), false));
    }

    private void answer(StatusQuote quote, UUID requesterId, UUID quotedAuthorId, boolean accepted) {
        var quotedAuthor = actors.findLocalById(quotedAuthorId);
        var requester = actors.findById(requesterId)
                .filter(actor -> actor.isActive() && !actors.isLocal(actor) && !blocks.isBlocked(actor.uri().getHost()));
        var quoting = statuses.findStored(quote.statusId());
        var quoted = statuses.findStored(quote.quotedStatusId());
        if (quotedAuthor.isEmpty() || requester.isEmpty() || quoting.isEmpty() || quoted.isEmpty()) {
            return;
        }
        var authorUri = quotedAuthor.get().uri();
        var requestId = quote.requestUri().toString();
        var inboxes = List.of(requester.get().inboxUri());
        if (!accepted) {
            var reject = QuoteRequestAnswer.reject(authorUri, requestId, requester.get().uri(), quoted.get().uri(),
                    quoting.get().uri());
            deliveries.enqueue(new OutboundActivity(reject.id().toString(), quotedAuthor.get().id(),
                    json.writeValueAsString(reject)), inboxes);
            return;
        }
        var accept = QuoteRequestAnswer.accept(authorUri, requestId, requester.get().uri(), quoted.get().uri(),
                quoting.get().uri(), QuoteAuthorization.idFor(authorUri, quote.stampId()));
        // An author who rejected the quote first has a Reject that may still be being sent, which can't arrive after this.
        var rejectId = QuoteRequestAnswer.reject(authorUri, requestId, requester.get().uri(), quoted.get().uri(),
                quoting.get().uri()).id().toString();
        deliveries.cancel(rejectId);
        deliveries.enqueueAfter(new OutboundActivity(accept.id().toString(), quotedAuthor.get().id(),
                json.writeValueAsString(accept)), inboxes, rejectId);
    }
}
