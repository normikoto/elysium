package dev.biribiri.elysium.activitypub.outbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity.WhileSuspended;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.activitypub.vocab.Update;
import dev.biribiri.elysium.activitypub.vocab.Vote;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.PollEnded;
import dev.biribiri.elysium.content.PollService;
import dev.biribiri.elysium.content.PollVoted;
import dev.biribiri.elysium.content.RemotePollRefresher;
import dev.biribiri.elysium.content.StatusService;
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
import java.util.UUID;

/**
 * Sends what polls need, as Mastodon does: a local user's votes in a poll on another server go to its author, and a local
 * poll's counts go to everyone who has it, and its remote voters, in an {@code Update}. That waits a few minutes after a
 * vote, so that a burst of votes is sent once, and is sent at once when the poll ends.
 */
@Service
public class PollDeliveries {

    static final TaskDescriptor<PollResultsJob> RESULTS_TASK = TaskDescriptor.of("poll-results", PollResultsJob.class);

    /**
     * As on Mastodon.
     */
    static final Duration RESULTS_AFTER = Duration.ofMinutes(3);

    private final StatusService statuses;
    private final PollService polls;
    private final ActorService actors;
    private final Notes notes;
    private final StatusDeliveries statusDeliveries;
    private final Recipients recipients;
    private final ActivityDeliveries deliveries;
    private final RemotePollRefresher refresher;
    private final JsonMapper json;
    private final SchedulerClient scheduler;
    private final TransactionTemplate transaction;

    /**
     * @param scheduler lazy, because the scheduler needs the results task, which needs this
     */
    PollDeliveries(
            StatusService statuses,
            PollService polls,
            ActorService actors,
            Notes notes,
            StatusDeliveries statusDeliveries,
            Recipients recipients,
            ActivityDeliveries deliveries,
            RemotePollRefresher refresher,
            JsonMapper json,
            @Lazy SchedulerClient scheduler,
            TransactionTemplate transaction) {
        this.statuses = statuses;
        this.polls = polls;
        this.actors = actors;
        this.notes = notes;
        this.statusDeliveries = statusDeliveries;
        this.recipients = recipients;
        this.deliveries = deliveries;
        this.refresher = refresher;
        this.json = json;
        this.scheduler = scheduler;
        this.transaction = transaction;
    }

    /**
     * A vote that's been removed since, such as by an edit of the poll, isn't sent.
     */
    @ApplicationModuleListener
    public void on(PollVoted event) {
        var status = statuses.findStored(event.statusId());
        if (status.isEmpty()) {
            return;
        }
        var author = actors.findById(status.get().actorId()).filter(found -> found.isActive());
        if (author.isEmpty()) {
            return;
        }
        if (actors.isLocal(author.get())) {
            var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            Jobs.scheduleAt(scheduler, RESULTS_TASK, new PollResultsJob(event.statusId(), author.get().id(), now),
                    now.plus(RESULTS_AFTER), RESULTS_TASK.getTaskName(), event.statusId().toString());
            return;
        }
        var voter = actors.findLocalById(event.voterId());
        if (voter.isEmpty()) {
            return;
        }
        for (var vote : polls.findVotes(event.voteIds())) {
            var activity = Vote.of(voter.get().uri(), vote.id(), vote.title(), status.get().uri(), author.get().uri());
            deliveries.enqueue(
                    new OutboundActivity(activity.id().toString(), voter.get().id(), json.writeValueAsString(activity),
                            WhileSuspended.DROP),
                    List.of(author.get().inboxUri()));
        }
    }

    /**
     * A local poll's final counts are sent at once. A remote one's are fetched from its server, which only some send.
     */
    @ApplicationModuleListener
    public void on(PollEnded event) {
        if (event.local()) {
            sendResults(event.statusId(), event.authorId());
        } else {
            refresher.refreshSoon(event.statusId());
        }
    }

    CompletionHandler<PollResultsJob> sendResults(PollResultsJob job) {
        transaction.executeWithoutResult(ignored -> sendResults(job.statusId(), job.authorId()));
        return Jobs.done();
    }

    /**
     * The {@code Update} keeps the {@code updated} of the status's last edit, since nothing it shows was edited, which
     * Mastodon reads as new counts rather than an edit. It's sent under an ID from now, so that each one is sent.
     */
    private void sendResults(UUID statusId, UUID authorId) {
        var status = statuses.findAndHoldAgainstEdits(statusId, authorId);
        var author = actors.findLocalById(authorId);
        if (status.isEmpty() || author.isEmpty()) {
            return;
        }
        var note = notes.of(status.get(), author.get());
        if (note.oneOf() == null && note.anyOf() == null) {
            return;
        }
        var voters = new LinkedHashSet<URI>();
        actors.findByIds(polls.findVoters(statusId)).forEach(voter -> recipients.add(voter, voters));
        statusDeliveries.sendUpdate(status.get(), author.get(), note, Update.of(note, Instant.now()), voters);
    }
}
