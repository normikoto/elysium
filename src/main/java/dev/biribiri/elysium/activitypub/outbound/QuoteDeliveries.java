package dev.biribiri.elysium.activitypub.outbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity.WhileSuspended;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.activitypub.vocab.QuoteAuthorization;
import dev.biribiri.elysium.activitypub.vocab.QuoteRequest;
import dev.biribiri.elysium.activitypub.vocab.Update;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.QuoteRequested;
import dev.biribiri.elysium.content.QuoteRevoked;
import dev.biribiri.elysium.content.QuoteService;
import dev.biribiri.elysium.content.QuoteState;
import dev.biribiri.elysium.content.StatusNoteChanged;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import org.springframework.context.annotation.Lazy;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sends what FEP-044f has a quote's author and the quoted author send each other: a {@code QuoteRequest} to the quoted
 * author's server, an {@code Update} of the quote once it's answered, and a {@code Delete} of a {@code QuoteAuthorization}
 * when a local author withdraws it. Each can run again, and sends the same activity under the same ID.
 */
@Service
public class QuoteDeliveries {

    static final TaskDescriptor<QuoteRequestResendJob> RESEND_TASK =
            TaskDescriptor.of("quote-request-resend", QuoteRequestResendJob.class);

    /**
     * Long enough for the quoted author's server to have stored the {@code Create}, which is sent at the same time.
     */
    static final Duration RESEND_AFTER = Duration.ofMinutes(1);

    private final StatusService statuses;
    private final QuoteService quotes;
    private final ActorService actors;
    private final Notes notes;
    private final StatusDeliveries statusDeliveries;
    private final Recipients recipients;
    private final ActivityDeliveries deliveries;
    private final JsonMapper json;
    private final SchedulerClient scheduler;
    private final TransactionTemplate transaction;

    /**
     * @param scheduler lazy, because the scheduler needs the resend task, which needs this
     */
    QuoteDeliveries(
            StatusService statuses,
            QuoteService quotes,
            ActorService actors,
            Notes notes,
            StatusDeliveries statusDeliveries,
            Recipients recipients,
            ActivityDeliveries deliveries,
            JsonMapper json,
            @Lazy SchedulerClient scheduler,
            TransactionTemplate transaction) {
        this.scheduler = scheduler;
        this.transaction = transaction;
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
     * Held until this commits, so that deleting the status either happens first, or finds the request to cancel.
     */
    @ApplicationModuleListener
    public void on(QuoteRequested event) {
        if (sendRequest(event.statusId(), event.authorId())) {
            var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            Jobs.scheduleAt(scheduler, RESEND_TASK, new QuoteRequestResendJob(event.statusId(), event.authorId(), now),
                    now.plus(RESEND_AFTER), RESEND_TASK.getTaskName(), event.statusId().toString());
        }
    }

    /**
     * Sends a request that's still unanswered once more, under the same ID. Mastodon handles a quote's {@code Create} and
     * its {@code QuoteRequest} at the same time, and drops the request without answering if it reads the quote while the
     * {@code Create} is half stored. Mastodon answers every copy, and notifies the quoted author each time, so a delivery
     * that's still being tried isn't queued twice.
     */
    CompletionHandler<QuoteRequestResendJob> resend(QuoteRequestResendJob job) {
        transaction.executeWithoutResult(ignored -> {
            if (quotes.find(job.statusId()).filter(quote -> quote.state() == QuoteState.PENDING).isPresent()) {
                sendRequest(job.statusId(), job.authorId());
            }
        });
        return Jobs.done();
    }

    /**
     * @return whether it was queued
     */
    private boolean sendRequest(UUID statusId, UUID authorId) {
        var status = statuses.findAndHold(statusId, authorId);
        var author = actors.findLocalById(authorId);
        var quote = quotes.find(statusId);
        if (status.isEmpty() || author.isEmpty() || quote.isEmpty() || quote.get().requestUri() == null) {
            return false;
        }
        var quotedAuthor = Optional.ofNullable(quote.get().quotedAccountId()).flatMap(actors::findById)
                .filter(actor -> actor.isActive() && !actors.isLocal(actor));
        if (quotedAuthor.isEmpty()) {
            return false;
        }
        var request = QuoteRequest.of(quote.get().requestUri(), notes.of(status.get(), author.get()),
                quote.get().quotedUri(), quotedAuthor.get().uri());
        deliveries.enqueue(
                new OutboundActivity(request.id().toString(), author.get().id(), json.writeValueAsString(request),
                        WhileSuspended.DROP),
                List.of(quotedAuthor.get().inboxUri()));
        return true;
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
        statusDeliveries.sendUpdate(
                status.get(), author.get(), note, Update.of(note.withUpdated(event.updatedAt()), event.updatedAt()));
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
