package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.content.internal.PollRepository;
import dev.biribiri.elysium.content.internal.StatusRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Polls and their votes. A poll's ID is its status's, since a status has at most one.
 *
 * <p>A remote poll's counts are what its server last said, as on Mastodon, since votes from other servers only reach the
 * poll's own. A vote from here adds to them until its server's next counts, which include it.
 */
@Service
public class PollService {

    /**
     * How old a remote poll's counts can be before a viewer asking for them has them fetched again.
     */
    public static final Duration STALE_AFTER = Duration.ofMinutes(5);

    private static final int ENDING_BATCH_SIZE = 100;

    private final PollRepository polls;
    private final StatusRepository statuses;
    private final StatusService statusService;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;

    PollService(
            PollRepository polls,
            StatusRepository statuses,
            StatusService statusService,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager) {
        this.polls = polls;
        this.statuses = statuses;
        this.statusService = statusService;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * @param viewer whose own votes to include, if anyone's
     * @return an entry for each status that has a poll
     */
    public Map<UUID, Poll> findAll(Collection<UUID> statusIds, Optional<UUID> viewer) {
        return polls.findAll(statusIds, viewer.orElse(null));
    }

    public Optional<Poll> find(UUID statusId, Optional<UUID> viewer) {
        return Optional.ofNullable(findAll(List.of(statusId), viewer).get(statusId));
    }

    /**
     * Whether a remote poll's counts are old enough to fetch again. One that ended, and was fetched since, won't change.
     */
    public boolean isStale(UUID statusId, Instant now) {
        return polls.find(statusId)
                .filter(poll -> !poll.local())
                .filter(poll -> poll.fetchedAt() == null || poll.fetchedAt().plus(STALE_AFTER).isBefore(now))
                .filter(poll -> !poll.isExpired(poll.fetchedAt() == null ? Instant.EPOCH : poll.fetchedAt()))
                .isPresent();
    }

    /**
     * Votes in a poll that the voter can see, as on Mastodon: once, for one option, or for any number of them in a poll
     * that allows that.
     *
     * @param choices the positions of the options chosen
     * @return the poll as the voter sees it now, or empty if there's no poll they can see
     * @throws InvalidVoteException if the poll has ended, they've voted already, or the choices don't fit the poll
     */
    public Optional<Poll> vote(UUID statusId, UUID voterId, List<Integer> choices) {
        if (statusService.findVisible(statusId, Optional.of(voterId)).isEmpty()) {
            return Optional.empty();
        }
        var voted = transaction.execute(tx -> {
            var found = polls.findAndLock(statusId);
            if (found.isEmpty()) {
                return false;
            }
            var poll = found.get();
            var now = now();
            if (poll.isExpired(now)) {
                throw new InvalidVoteException("The poll has already ended");
            }
            if (!polls.votedPositions(statusId, voterId).isEmpty()) {
                throw new InvalidVoteException("You have already voted on this poll");
            }
            if (choices.isEmpty()) {
                throw new InvalidVoteException("Choose at least one option");
            }
            if (Set.copyOf(choices).size() != choices.size()) {
                throw new InvalidVoteException("The same option can't be chosen twice");
            }
            if (!poll.multiple() && choices.size() > 1) {
                throw new InvalidVoteException("This poll only allows one choice");
            }
            if (choices.stream().anyMatch(choice -> choice < 0 || choice >= poll.titles().size())) {
                throw new InvalidVoteException("There's no such option");
            }
            var ids = new ArrayList<UUID>();
            for (var choice : choices.stream().sorted().toList()) {
                var id = Ids.timeOrdered(now);
                polls.insertVote(id, statusId, voterId, choice, null, now);
                ids.add(id);
            }
            if (!poll.local()) {
                polls.addRemoteVotes(statusId, choices, true);
            }
            events.publishEvent(new PollVoted(statusId, voterId, ids));
            return true;
        });
        return Boolean.TRUE.equals(voted) ? find(statusId, Optional.of(voterId)) : Optional.empty();
    }

    /**
     * What {@link #receiveVote} did with a vote.
     */
    public enum VoteOutcome {
        COUNTED,
        /** It's been stored before, so this is a repeat. */
        REPEATED,
        /** The status has no poll, or isn't this server's. */
        NO_POLL,
        ENDED,
        /** No option has the name it chose. */
        NO_OPTION,
        /** The voter has voted already, for this option, or for any in a poll that only allows one. */
        ALREADY_VOTED
    }

    /**
     * Counts a vote that another server sent for a local poll. Whether the voter can see it has to be checked first.
     *
     * @param title the name of the option it chose, which has to match exactly, as on Mastodon
     */
    public VoteOutcome receiveVote(UUID statusId, UUID voterId, URI voteUri, String title) {
        return transaction.execute(tx -> {
            var found = polls.findAndLock(statusId).filter(PollRepository.Row::local);
            if (found.isEmpty()) {
                return VoteOutcome.NO_POLL;
            }
            var poll = found.get();
            var now = now();
            if (poll.isExpired(now)) {
                return VoteOutcome.ENDED;
            }
            var position = poll.titles().indexOf(title);
            if (position < 0) {
                return VoteOutcome.NO_OPTION;
            }
            var had = polls.votedPositions(statusId, voterId);
            if (had.contains(position) || (!poll.multiple() && !had.isEmpty())) {
                return VoteOutcome.ALREADY_VOTED;
            }
            var id = Ids.timeOrdered(now);
            if (!polls.insertVote(id, statusId, voterId, position, voteUri, now)) {
                return VoteOutcome.REPEATED;
            }
            events.publishEvent(new PollVoted(statusId, voterId, List.of(id)));
            return VoteOutcome.COUNTED;
        });
    }

    /**
     * Replaces a remote poll's counts and closing time with what its server says now, if it has the same options. One
     * whose options have changed is changed by an edit, through {@link StatusService#updateRemote}, which resets its votes.
     *
     * @return whether it was stored with these options
     */
    public boolean refreshRemote(URI uri, UUID authorId, RemotePoll remote) {
        var status = statuses.findByUriForAuthor(uri, authorId);
        if (status.isEmpty()) {
            return false;
        }
        var statusId = status.get().id();
        var poll = clean(remote);
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            var found = polls.findAndLock(statusId).filter(row -> !row.local());
            if (found.isEmpty() || !found.get().titles().equals(poll.options())
                    || found.get().multiple() != poll.multiple()) {
                return false;
            }
            polls.refreshRemote(statusId, poll.counts(), poll.votersCount(), poll.expiresAt(), poll.closedAt(), now());
            return true;
        }));
    }

    /**
     * Notes that a remote poll was fetched again, even if that found nothing new, so that it isn't fetched again soon.
     */
    public void markFetched(UUID statusId) {
        polls.markFetched(statusId, now());
    }

    /**
     * Everyone who's voted in a poll, here or elsewhere, in no order.
     */
    public List<UUID> findVoters(UUID statusId) {
        return polls.findVoters(statusId);
    }

    public List<PollVote> findVotes(Collection<UUID> voteIds) {
        return polls.findVotes(voteIds);
    }

    /**
     * Publishes {@link PollEnded} for each poll whose voting has closed since this last ran.
     *
     * @return how many there were
     */
    public int endDue() {
        var total = 0;
        int ended;
        do {
            var batch = transaction.execute(tx -> {
                var due = polls.endDue(now(), ENDING_BATCH_SIZE);
                due.forEach(poll -> events.publishEvent(new PollEnded(poll.statusId(), poll.authorId(), poll.local())));
                return due.size();
            });
            ended = batch == null ? 0 : batch;
            total += ended;
        } while (ended == ENDING_BATCH_SIZE);
        return total;
    }

    /**
     * Removes an actor's votes. A remote poll keeps its counts until its server sends new ones.
     */
    public void removeForActor(UUID actorId) {
        polls.removeForActor(actorId);
    }

    public void removeForDomain(String domain) {
        var after = new UUID(0, 0);
        List<UUID> batch;
        do {
            batch = polls.votersOn(domain, after, 1000);
            if (!batch.isEmpty()) {
                polls.removeForActors(batch);
                after = batch.getLast();
            }
        } while (batch.size() == 1000);
    }

    /**
     * Checks a poll for a new local status.
     *
     * @throws InvalidStatusException if it breaks one of the limits in {@link Poll}
     */
    static NewPoll check(NewPoll poll) {
        var options = poll.options().stream().map(String::strip).toList();
        if (options.size() < 2) {
            throw new InvalidStatusException("A poll needs at least 2 options");
        }
        if (options.size() > Poll.MAX_OPTIONS) {
            throw new InvalidStatusException("A poll can't have more than %d options".formatted(Poll.MAX_OPTIONS));
        }
        if (options.stream().anyMatch(String::isEmpty)) {
            throw new InvalidStatusException("A poll's options can't be blank");
        }
        if (options.stream().anyMatch(option -> option.codePointCount(0, option.length()) > Poll.MAX_OPTION_CHARACTERS)) {
            throw new InvalidStatusException(
                    "A poll's options can't be longer than %d characters".formatted(Poll.MAX_OPTION_CHARACTERS));
        }
        if (Set.copyOf(options).size() != options.size()) {
            throw new InvalidStatusException("A poll's options have to be different");
        }
        if (poll.expiresIn() < Poll.MIN_EXPIRATION) {
            throw new InvalidStatusException("A poll has to be open for at least %d seconds".formatted(Poll.MIN_EXPIRATION));
        }
        if (poll.expiresIn() > Poll.MAX_EXPIRATION) {
            throw new InvalidStatusException("A poll can't be open for more than %d seconds".formatted(Poll.MAX_EXPIRATION));
        }
        return new NewPoll(options, poll.expiresIn(), poll.multiple(), poll.hideTotals());
    }

    /**
     * Keeps a remote poll within {@link RemotePoll#MAX_OPTIONS} and {@link RemotePoll#MAX_OPTION_CHARACTERS}, rather than
     * refusing the status, and counts below zero as none.
     */
    static RemotePoll clean(RemotePoll remote) {
        var size = Math.min(remote.options().size(), RemotePoll.MAX_OPTIONS);
        var options = remote.options().subList(0, size).stream().map(PollService::truncate).toList();
        var counts = remote.counts().subList(0, size).stream().map(count -> Math.max(count, 0)).toList();
        var voters = remote.votersCount() == null ? null : Math.max(remote.votersCount(), 0);
        return new RemotePoll(options, counts, remote.multiple(), truncate(remote.expiresAt()),
                truncate(remote.closedAt()), voters);
    }

    private static String truncate(String option) {
        var stripped = option.strip();
        if (stripped.codePointCount(0, stripped.length()) <= RemotePoll.MAX_OPTION_CHARACTERS) {
            return stripped;
        }
        return stripped.substring(0, stripped.offsetByCodePoints(0, RemotePoll.MAX_OPTION_CHARACTERS));
    }

    private static Instant truncate(Instant at) {
        return at == null ? null : at.truncatedTo(ChronoUnit.MICROS);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
