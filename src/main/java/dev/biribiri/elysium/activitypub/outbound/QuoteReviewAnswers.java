package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.vocab.QuoteAuthorization;
import dev.biribiri.elysium.activitypub.vocab.QuoteRequestAnswer;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.QuoteReviewed;
import dev.biribiri.elysium.content.QuoteService;
import dev.biribiri.elysium.content.QuoteState;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Answers another server's {@code QuoteRequest} once the quoted author has reviewed it by hand, with the same
 * {@code Accept} or {@code Reject} that answering it straight away would have sent. Only what the quote is now is sent,
 * so that this running again, or after the author changed their mind, sends nothing that's since been undone.
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
        var quotedAuthor = actors.findLocalById(event.quotedAuthorId());
        var requester = actors.findById(event.authorId())
                .filter(actor -> actor.isActive() && !actors.isLocal(actor) && !blocks.isBlocked(actor.uri().getHost()));
        var quoting = statuses.findStored(event.statusId());
        var quoted = statuses.findStored(quote.get().quotedStatusId());
        if (quotedAuthor.isEmpty() || requester.isEmpty() || quoting.isEmpty() || quoted.isEmpty()) {
            return;
        }
        var authorUri = quotedAuthor.get().uri();
        var authorization = event.accepted() ? QuoteAuthorization.idFor(authorUri, quote.get().stampId()) : null;
        var answer = QuoteRequestAnswer.of(authorUri, quote.get().requestUri().toString(), requester.get().uri(),
                quoted.get().uri(), quoting.get().uri(), authorization);
        deliveries.enqueue(
                new OutboundActivity(answer.id().toString(), quotedAuthor.get().id(), json.writeValueAsString(answer)),
                List.of(requester.get().inboxUri()));
    }
}
