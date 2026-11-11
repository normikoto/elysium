package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity.WhileSuspended;
import dev.biribiri.elysium.activitypub.vocab.Create;
import dev.biribiri.elysium.activitypub.vocab.QuoteAuthorization;
import dev.biribiri.elysium.activitypub.vocab.QuoteRequest;
import dev.biribiri.elysium.activitypub.vocab.Update;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.QuoteRequested;
import dev.biribiri.elysium.content.QuoteRevoked;
import dev.biribiri.elysium.content.QuoteService;
import dev.biribiri.elysium.content.StatusNoteChanged;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Sends what FEP-044f has a quote's author and the quoted author send each other: a {@code QuoteRequest} to the quoted
 * author's server, an {@code Update} of the quote once it's answered, and a {@code Delete} of a {@code QuoteAuthorization}
 * when a local author withdraws it. Each can run again, and sends the same activity under the same ID.
 */
@Service
public class QuoteDeliveries {

    private final StatusService statuses;
    private final QuoteService quotes;
    private final ActorService actors;
    private final Notes notes;
    private final StatusDeliveries statusDeliveries;
    private final Recipients recipients;
    private final ActivityDeliveries deliveries;
    private final JsonMapper json;

    QuoteDeliveries(
            StatusService statuses,
            QuoteService quotes,
            ActorService actors,
            Notes notes,
            StatusDeliveries statusDeliveries,
            Recipients recipients,
            ActivityDeliveries deliveries,
            JsonMapper json) {
        this.statuses = statuses;
        this.quotes = quotes;
        this.actors = actors;
        this.notes = notes;
        this.statusDeliveries = statusDeliveries;
        this.recipients = recipients;
        this.deliveries = deliveries;
        this.json = json;
    }

    /**
     * The ID of a quote's {@code QuoteRequest}, which is the one that {@code content} stores for it. A status's quote can't
     * change, so it never needs another.
     */
    public static String requestIdFor(URI statusUri) {
        return statusUri + "#quote_request";
    }

    /**
     * Held until this commits, so that deleting the status either happens first, or finds the request to cancel.
     */
    @ApplicationModuleListener
    public void on(QuoteRequested event) {
        var status = statuses.findAndHold(event.statusId(), event.authorId());
        var author = actors.findLocalById(event.authorId());
        var quote = quotes.find(event.statusId());
        if (status.isEmpty() || author.isEmpty() || quote.isEmpty() || quote.get().requestUri() == null) {
            return;
        }
        var quotedAuthor = Optional.ofNullable(quote.get().quotedAccountId()).flatMap(actors::findById)
                .filter(actor -> actor.isActive() && !actors.isLocal(actor));
        if (quotedAuthor.isEmpty()) {
            return;
        }
        var request = QuoteRequest.of(quote.get().requestUri(), notes.of(status.get(), author.get()),
                quote.get().quotedUri(), quotedAuthor.get().uri());
        deliveries.enqueue(
                new OutboundActivity(request.id().toString(), author.get().id(), json.writeValueAsString(request),
                        WhileSuspended.DROP),
                List.of(quotedAuthor.get().inboxUri()));
    }

    /**
     * Sends the status as it is now, as for an edit, under an ID from when it changed, after any {@code Create} still being
     * sent. Its {@code updated} is that time too, so that a server that's had an edit ignores this, since the edit's
     * {@code Update} already showed what this does. The status can't be edited while this runs.
     */
    @ApplicationModuleListener
    public void on(StatusNoteChanged event) {
        var status = statuses.findAndHoldAgainstEdits(event.statusId(), event.authorId());
        var author = actors.findLocalById(event.authorId());
        if (status.isEmpty() || author.isEmpty()) {
            return;
        }
        if (status.get().editedAt() != null && status.get().editedAt().isAfter(event.updatedAt())) {
            return;
        }
        var note = notes.of(status.get(), author.get());
        var create = Create.of(note);
        deliveries.replaceBody(create.id().toString(), json.writeValueAsString(create));
        var inboxes = statusDeliveries.everyoneSent(status.get(), true);
        if (inboxes.isEmpty()) {
            return;
        }
        var update = Update.of(note.withUpdated(event.updatedAt()), event.updatedAt());
        deliveries.enqueueAfter(
                new OutboundActivity(
                        update.id().toString(), author.get().id(), json.writeValueAsString(update), WhileSuspended.DROP),
                inboxes,
                create.id().toString());
    }

    /**
     * Tells the quote's author's server, and the quoted author's followers, who may have seen the quote, that the
     * {@code QuoteAuthorization} is gone. A local quote is also sent to everyone who has it, along with its {@code Update}.
     */
    @ApplicationModuleListener
    public void on(QuoteRevoked event) {
        if (event.stampId() == null) {
            return;
        }
        var quotedAuthor = actors.findLocalById(event.quotedAuthorId());
        var quotingAuthor = actors.findById(event.authorId());
        if (quotedAuthor.isEmpty() || quotingAuthor.isEmpty()) {
            return;
        }
        var stamp = QuoteAuthorization.idFor(quotedAuthor.get().uri(), event.stampId());
        var inboxes = new LinkedHashSet<URI>();
        if (actors.isLocal(quotingAuthor.get())) {
            statuses.findVisible(event.statusId(), Optional.of(event.authorId()))
                    .ifPresent(status -> inboxes.addAll(statusDeliveries.everyoneSent(status, false)));
        } else {
            recipients.add(quotingAuthor.get(), inboxes);
        }
        recipients.addFollowers(quotedAuthor.get().id(), Visibility.PUBLIC, null, inboxes);
        if (inboxes.isEmpty()) {
            return;
        }
        var delete = QuoteAuthorization.deleteOf(stamp, quotedAuthor.get().uri(), List.of(quotingAuthor.get().uri().toString()));
        deliveries.enqueue(
                new OutboundActivity(delete.id().toString(), quotedAuthor.get().id(), json.writeValueAsString(delete)),
                List.copyOf(inboxes));
    }
}
