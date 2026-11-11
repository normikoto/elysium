package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.QuoteDecision;
import dev.biribiri.elysium.content.QuoteService;
import dev.biribiri.elysium.content.QuoteState;
import dev.biribiri.elysium.content.QuoteVerificationNeeded;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusQuote;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Checks whether another server's quote is approved, as FEP-044f says to, before it shows what it quotes: it's the
 * author's own status, or the quoted author's server serves a {@code QuoteAuthorization} that names both statuses and is
 * the quoted author's. A quote of a local status is decided by this server's own record, and one from a server that can't
 * ask for approval needs none, unless it's of a local status whose author wouldn't have approved it.
 *
 * <p>The quoted status is fetched if it isn't stored, signed as the instance actor, as a boosted one is, and only if it's
 * one that anyone can see. The quote's server chose both URLs, so each fetch takes a turn in {@link TargetFetchTurns}.
 * A quote that can't be checked stays pending, and isn't shown.
 */
@Component
class QuoteVerifications {

    static final TaskDescriptor<QuoteVerificationJob> TASK =
            TaskDescriptor.of("quote-verification", QuoteVerificationJob.class);

    private static final Logger log = LoggerFactory.getLogger(QuoteVerifications.class);

    private final QuoteService quotes;
    private final StatusService statuses;
    private final ActorService actors;
    private final NoteStorage storage;
    private final ActivityDocuments documents;
    private final TargetFetchTurns turns;
    private final DomainBlocks blocks;
    private final SchedulerClient scheduler;

    /**
     * @param scheduler lazy, because the scheduler needs this task, which needs this
     */
    QuoteVerifications(
            QuoteService quotes,
            StatusService statuses,
            ActorService actors,
            NoteStorage storage,
            ActivityDocuments documents,
            TargetFetchTurns turns,
            DomainBlocks blocks,
            @Lazy SchedulerClient scheduler) {
        this.quotes = quotes;
        this.statuses = statuses;
        this.actors = actors;
        this.storage = storage;
        this.documents = documents;
        this.turns = turns;
        this.blocks = blocks;
        this.scheduler = scheduler;
    }

    /**
     * The job is named by what's being checked, so that a repeat queues nothing, and a new {@code QuoteAuthorization}
     * gets a check of its own.
     */
    @ApplicationModuleListener
    public void on(QuoteVerificationNeeded event) {
        quotes.find(event.statusId()).filter(quote -> quote.state() == QuoteState.PENDING).ifPresent(quote -> {
            var approval = Objects.toString(quote.approvalUri(), "");
            Jobs.schedule(scheduler, TASK,
                    new QuoteVerificationJob(quote.statusId(), quote.quotedUri().toString(),
                            quote.approvalUri() == null ? null : approval, event.fetchQuoted(),
                            Instant.now().truncatedTo(ChronoUnit.MICROS), null, null),
                    "quote-verification", quote.statusId().toString(), quote.quotedUri().toString(), approval,
                    Boolean.toString(event.fetchQuoted()));
        });
    }

    /**
     * @return what to do with the job, which is to wait for a turn if one hasn't come yet
     * @throws IllegalStateException if a server couldn't be reached, which is worth trying again
     */
    CompletionHandler<QuoteVerificationJob> run(QuoteVerificationJob job) {
        var quotedUri = URI.create(job.quotedUri());
        var approvalUri = job.approvalUri() == null ? null : URI.create(job.approvalUri());
        var quote = quotes.find(job.statusId()).filter(found -> found.state() == QuoteState.PENDING
                && found.quotedUri().equals(quotedUri) && Objects.equals(found.approvalUri(), approvalUri));
        var quoting = quote.flatMap(found -> statuses.findStored(found.statusId()));
        if (quote.isEmpty() || quoting.isEmpty()) {
            return Jobs.done();
        }
        try {
            var quoted = quotedOf(quote.get());
            if (quoted.isEmpty() && !quote.get().linked() && job.fetchQuoted()) {
                var fetched = fetchQuoted(job, quotedUri);
                if (fetched.isPresent()) {
                    return fetched.get();
                }
                quoted = quotes.find(job.statusId()).flatMap(this::quotedOf);
            }
            if (quoted.isEmpty()) {
                return Jobs.done();
            }
            return check(job, quote.get(), quoting.get(), quoted.get(), approvalUri);
        } catch (PermanentJobFailure e) {
            // Such as an author who's been suspended here, which isn't worth parking the job for.
            log.debug("Not checking the quote of {}: {}", job.quotedUri(), e.getMessage());
            return Jobs.done();
        }
    }

    private Optional<Status> quotedOf(StatusQuote quote) {
        return Optional.ofNullable(quote.quotedStatusId()).flatMap(statuses::findStored);
    }

    /**
     * @return what to do with the job if it has to wait, or empty once the quoted status is stored, or can't be
     */
    private Optional<CompletionHandler<QuoteVerificationJob>> fetchQuoted(QuoteVerificationJob job, URI quotedUri) {
        if (blocks.isBlocked(quotedUri.getHost())) {
            return Optional.of(Jobs.done());
        }
        var turn = take(quotedUri, job.noteTurn());
        if (turn.isEmpty()) {
            return Optional.of(Jobs.done());
        }
        if (turn.get().isAfter(Instant.now())) {
            return Optional.of(Jobs.rescheduleAt(turn.get(), job.withNoteTurn(turn.get())));
        }
        var note = fetch(quotedUri).flatMap(RemoteNote::read).filter(found -> found.id().equals(quotedUri));
        if (note.isEmpty()) {
            return Optional.of(Jobs.done());
        }
        var author = note.get().attributedTo().size() == 1
                ? ActivityObject.parseUri(note.get().attributedTo().getFirst())
                : Optional.<URI>empty();
        // Only the status's own server can say who wrote it.
        if (author.isEmpty() || !Inbox.isOnActorsServer(quotedUri, author.get())) {
            log.debug("Not checking a quote of {}, which isn't attributed to one actor on its server", quotedUri);
            return Optional.of(Jobs.done());
        }
        storage.storeQuoted(note.get(), author.get());
        return Optional.empty();
    }

    private CompletionHandler<QuoteVerificationJob> check(
            QuoteVerificationJob job, StatusQuote quote, Status quoting, Status quoted, URI approvalUri) {
        if (quoted.actorId().equals(quoting.actorId())) {
            quotes.verified(quote.statusId(), quote.quotedUri(), approvalUri, QuoteService.Verification.ACCEPTED);
            return Jobs.done();
        }
        var quotedAuthor = actors.findById(quoted.actorId()).filter(Actor::isActive);
        if (quotedAuthor.isEmpty()) {
            return Jobs.done();
        }
        if (actors.isLocal(quotedAuthor.get())) {
            // Approval is given here, in answer to a QuoteRequest, so only a server that can't ask is decided now.
            if (quote.legacy()) {
                var decision = quotes.decide(quoted, quoting.actorId());
                quotes.verified(quote.statusId(), quote.quotedUri(), approvalUri, decision == QuoteDecision.AUTOMATIC
                        ? QuoteService.Verification.ACCEPTED : QuoteService.Verification.REJECTED);
            }
            return Jobs.done();
        }
        if (quote.legacy()) {
            quotes.verified(quote.statusId(), quote.quotedUri(), approvalUri, QuoteService.Verification.ACCEPTED);
            return Jobs.done();
        }
        if (approvalUri == null || quotes.wasApprovalDeleted(approvalUri, quotedAuthor.get().uri())
                || !Inbox.isOnActorsServer(approvalUri, quotedAuthor.get().uri()) || blocks.isBlocked(approvalUri.getHost())) {
            return Jobs.done();
        }
        var turn = take(approvalUri, job.stampTurn());
        if (turn.isEmpty()) {
            return Jobs.done();
        }
        if (turn.get().isAfter(Instant.now())) {
            return Jobs.rescheduleAt(turn.get(), job.withStampTurn(turn.get()));
        }
        var stamp = fetch(approvalUri);
        if (stamp.isPresent() && isAuthorization(stamp.get(), approvalUri, quotedAuthor.get().uri(), quoting.uri(),
                quoted.uri())) {
            quotes.verified(quote.statusId(), quote.quotedUri(), approvalUri, QuoteService.Verification.ACCEPTED);
        } else {
            log.debug("Not approving the quote {}, whose QuoteAuthorization doesn't match", quoting.uri());
        }
        return Jobs.done();
    }

    /**
     * FEP-044f's checks: it's what it says it is, from the quoted author, about these two statuses.
     */
    static boolean isAuthorization(JsonNode stamp, URI id, URI quotedAuthor, URI quote, URI quoted) {
        Function<String, Optional<URI>> idOf = property ->
                Optional.ofNullable(ActivityJson.idOf(stamp.path(property))).flatMap(ActivityObject::parseUri);
        return ActivityJson.types(stamp.path("type")).contains("QuoteAuthorization")
                && idOf.apply("id").equals(Optional.of(id))
                && ActivityJson.ids(stamp.path("attributedTo")).equals(List.of(quotedAuthor.toString()))
                && idOf.apply("interactingObject").equals(Optional.of(quote))
                && idOf.apply("interactionTarget").equals(Optional.of(quoted));
    }

    private Optional<Instant> take(URI target, Instant held) {
        var turn = turns.take(target, held, Instant.now());
        if (turn.isEmpty()) {
            log.debug("Not checking a quote, since too many fetches are waiting for {}", target.getHost());
        }
        return turn;
    }

    /**
     * @return the document, or empty if it's gone, or can't be read
     * @throws IllegalStateException if its server couldn't be reached, which is worth trying again
     */
    private Optional<JsonNode> fetch(URI id) {
        try {
            return Optional.of(documents.fetch(id));
        } catch (DocumentFetchException e) {
            if (e.isTemporary()) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            log.debug("Not checking a quote, since {} couldn't be fetched: {}", id, e.getMessage());
            return Optional.empty();
        }
    }
}
