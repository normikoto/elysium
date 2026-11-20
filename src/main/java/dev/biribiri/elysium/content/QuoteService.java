package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.content.internal.QuoteRepository;
import dev.biribiri.elysium.content.internal.StatusRepository;
import dev.biribiri.elysium.graph.BlockService;
import dev.biribiri.elysium.graph.FollowService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Quotes of statuses, and who can quote them, as FEP-044f has it: a quote of someone else's status only shows it once its
 * author has approved, which their server proves with a {@code QuoteAuthorization}. A quote between servers that can't
 * ask for that, which is how quotes worked before, is shown without one.
 *
 * <p>A status that's quoted is never changed by being quoted, so these hold the quoting status. Each change of state
 * happens once, under a lock on the quote, so that repeated activities and jobs change nothing the second time.
 */
@Service
public class QuoteService {

    private final QuoteRepository quotes;
    private final StatusRepository statuses;
    private final StatusService statusService;
    private final ActorService actors;
    private final QuoteRules rules;
    private final FollowService follows;
    private final BlockService blocks;
    private final PostingDefaults postingDefaults;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;

    QuoteService(
            QuoteRepository quotes,
            StatusRepository statuses,
            StatusService statusService,
            ActorService actors,
            QuoteRules rules,
            FollowService follows,
            BlockService blocks,
            PostingDefaults postingDefaults,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager) {
        this.quotes = quotes;
        this.statuses = statuses;
        this.statusService = statusService;
        this.actors = actors;
        this.rules = rules;
        this.follows = follows;
        this.blocks = blocks;
        this.postingDefaults = postingDefaults;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public Optional<StatusQuote> find(UUID statusId) {
        return quotes.find(statusId);
    }

    /**
     * @return an entry for each status that quotes anything
     */
    public Map<UUID, StatusQuote> findAll(Collection<UUID> statusIds) {
        return quotes.findAll(statusIds);
    }

    /**
     * Counts each status's accepted quotes that anyone can see.
     *
     * @return an entry for each status that has any
     */
    public Map<UUID, Long> acceptedCounts(Collection<UUID> statusIds) {
        return quotes.acceptedCounts(statusIds);
    }

    /**
     * When a local status's {@code Note} last changed without an edit, if it has.
     */
    public Optional<Instant> noteUpdatedAt(UUID statusId) {
        return statuses.findById(statusId).map(Status::noteUpdatedAt);
    }

    /**
     * What would happen to a quote of this status by this account.
     */
    public QuoteDecision decide(Status quoted, UUID quoter) {
        return rules.decide(quoted, quoted.quoteApproval(), actors.isLocal(quoted.actorId()), quoter);
    }

    /**
     * Like {@link #decide}, for each of these statuses and one viewer, with one lookup of each kind.
     *
     * @param localAuthors which of the statuses' authors are this server's
     * @return an entry for each status
     */
    public Map<UUID, QuoteDecision> decide(
            Collection<Status> quoted, Set<UUID> localAuthors, Optional<UUID> viewer) {
        var authors = quoted.stream().map(Status::actorId).collect(Collectors.toSet());
        var needsFollows = viewer.isPresent() && quoted.stream().map(Status::quoteApproval).filter(Objects::nonNull)
                .anyMatch(QuoteService::includesFollowers);
        Set<UUID> followed = needsFollows ? Set.copyOf(follows.followedIds(viewer.get())) : Set.of();
        Set<UUID> refusing = viewer.map(id -> blocks.refusing(id, authors)).orElse(Set.of());
        var decisions = new HashMap<UUID, QuoteDecision>();
        for (var status : quoted) {
            decisions.put(status.id(), QuoteRules.decide(status, status.quoteApproval(),
                    localAuthors.contains(status.actorId()), viewer,
                    (follower, followedId) -> viewer.filter(follower::equals).isPresent()
                            ? followed.contains(followedId) : follows.isFollowing(follower, followedId),
                    (author, quoter) -> refusing.contains(author)));
        }
        return decisions;
    }

    private static boolean includesFollowers(QuoteApproval approval) {
        return approval.automatic().contains(QuoteApproval.Audience.FOLLOWERS)
                || approval.manual().contains(QuoteApproval.Audience.FOLLOWERS);
    }

    /**
     * Lists the accepted quotes of a status that this viewer can see, newest first.
     */
    public List<Status> findAccepted(UUID quotedStatusId, UUID viewer, StatusPage page) {
        var ids = quotes.pageAccepted(quotedStatusId, page.maxId(), page.minId() != null ? page.minId() : page.sinceId(),
                page.limit());
        return statusService.findVisible(ids, viewer).stream().sorted(Comparator.comparing(Status::id).reversed()).toList();
    }

    /**
     * The local statuses whose accepted quote is of this status.
     *
     * @return each quoting status, with its author
     */
    public Map<UUID, UUID> findLocalQuotes(UUID quotedStatusId) {
        return quotes.findLocalAcceptedQuotes(quotedStatusId);
    }

    /**
     * Finds a {@code QuoteAuthorization} that this server gave for this author's status, while it's still in force.
     *
     * @param signer who's asking, or empty for someone anonymous
     * @return empty if there's no such stamp, or the signer can't see the quote
     */
    public Optional<QuoteStamp> findStampFor(UUID stampId, UUID authorId, Optional<UUID> signer) {
        return quotes.findByStamp(stampId)
                .filter(found -> found.state() == QuoteState.ACCEPTED && authorId.equals(found.quotedAccountId())
                        && found.quotedStatusId() != null)
                .flatMap(found -> statusService.findStored(found.statusId())
                        .filter(quoting -> canSeeQuote(quoting, signer))
                        .flatMap(quoting -> statusService.findStored(found.quotedStatusId())
                                .map(quoted -> new QuoteStamp(quoting, quoted))));
    }

    /**
     * FEP-044f has a stamp served to whoever can see the quote, so that it tells nobody else the quote exists. A local
     * quote is checked as its {@code Note} is. A remote one can only be seen by its author and those it mentions.
     */
    private boolean canSeeQuote(Status quoting, Optional<UUID> signer) {
        if (quoting.isVisibleTo(Optional.empty())) {
            return true;
        }
        if (signer.isEmpty()) {
            return false;
        }
        if (actors.isLocal(quoting.actorId())) {
            return statusService.findFetchable(quoting.id(), signer).isPresent();
        }
        return signer.get().equals(quoting.actorId()) || statusService.findAllMentioned(quoting.id()).contains(signer.get());
    }

    public enum Revocation {
        REVOKED,
        NOT_FOUND,
        NOT_OWNED
    }

    /**
     * Withdraws a local author's approval of a quote of their status, which hides the status from the quote. A repeat
     * changes nothing. A quote that was never approved can be revoked too, so that it isn't approved later.
     */
    public Revocation revoke(UUID quotedStatusId, UUID quotingStatusId, UUID ownerId) {
        return transaction.execute(tx -> {
            var quoted = statusService.findVisible(quotedStatusId, Optional.of(ownerId));
            if (quoted.isEmpty()) {
                return Revocation.NOT_FOUND;
            }
            if (!quoted.get().actorId().equals(ownerId)) {
                return Revocation.NOT_OWNED;
            }
            var quote = quotes.findAndLock(quotingStatusId).filter(found -> quotedStatusId.equals(found.quotedStatusId()));
            if (quote.isEmpty()) {
                return Revocation.NOT_FOUND;
            }
            if (quote.get().state() != QuoteState.REVOKED) {
                revokeLocked(quote.get(), ownerId);
            }
            return Revocation.REVOKED;
        });
    }

    /**
     * Revokes the quoter's accepted and pending quotes of a local author's statuses, as though the author had revoked each,
     * once the author has blocked them. Lifting the block doesn't bring them back. Mastodon doesn't revoke them at all.
     */
    public void revokeQuotesBy(UUID quotedAuthorId, UUID quoterId) {
        if (!actors.isLocal(quotedAuthorId)) {
            return;
        }
        transaction.executeWithoutResult(tx -> {
            for (var quote : quotes.findByQuoterAndLock(quotedAuthorId, quoterId)) {
                revokeLocked(quote, quotedAuthorId);
            }
        });
    }

    private void revokeLocked(StatusQuote found, UUID ownerId) {
        quotes.changeState(found.statusId(), QuoteState.REVOKED, found.legacy(), null, found.stampId());
        // Even for a suspended quoter, whom nothing is sent to, since the quoted author's followers may have seen the quote.
        statuses.findForModeration(found.statusId()).ifPresent(status -> events.publishEvent(new QuoteRevoked(
                found.statusId(), status.actorId(), ownerId,
                found.state() == QuoteState.ACCEPTED ? found.stampId() : null, status.localOnly(),
                found.state() == QuoteState.PENDING && found.requestUri() != null)));
        statuses.findById(found.statusId()).ifPresent(this::noteChanged);
    }

    /**
     * Changes whose quotes of one of the author's statuses are approved automatically, and whether everyone else's are
     * rejected or reviewed by the author. It doesn't change what's been approved already, as on Mastodon, nor what's
     * waiting for them to review. A followers-only or direct status can
     * only be quoted by its author, whatever it's set to.
     *
     * @param policy whose quotes are approved automatically, or null to keep that
     * @param others what happens to everyone else's, or null to keep that
     * @return the status, or empty if the author has no status with this ID
     */
    public Optional<Status> setPolicy(UUID statusId, UUID authorId, QuotePolicy policy, OtherQuotes others) {
        return transaction.execute(tx -> {
            var status = statuses.findByIdForEdit(statusId)
                    .filter(found -> found.actorId().equals(authorId) && !found.isReblog());
            if (status.isEmpty() || !status.get().visibility().isOpen()) {
                return status;
            }
            var approval = StatusService.changedPolicy(status.get().quoteApproval(), policy, others,
                    () -> postingDefaults.quoteOthers(authorId));
            if (Objects.equals(approval, status.get().quoteApproval())) {
                return status;
            }
            quotes.setPolicy(statusId, approval);
            noteChanged(status.get());
            return statuses.findById(statusId);
        });
    }

    public enum Settlement {
        ACCEPTED,
        REJECTED,
        /** Only a {@code QuoteAuthorization} from the quoted author's server can approve it. */
        NEEDS_PROOF,
        /** Nothing can be decided now, so it isn't shown. */
        LEFT_PENDING,
        /** Its quoted author, who's local, approves quotes by hand. */
        NEEDS_REVIEW
    }

    /**
     * Decides a remote quote as far as can be done without asking the quoted author's server. A quote of one's own status
     * needs nobody's approval, and one from a server that can't ask is taken as it is, unless it's of a local status.
     */
    public Settlement settleWithoutProof(StatusQuote quote, Status quoting, Status quoted) {
        if (quoted.actorId().equals(quoting.actorId())) {
            return Settlement.ACCEPTED;
        }
        var quotedAuthor = actors.findById(quoted.actorId()).filter(Actor::isActive);
        if (quotedAuthor.isEmpty()) {
            return Settlement.LEFT_PENDING;
        }
        if (actors.isLocal(quotedAuthor.get())) {
            // Approval is given here, in answer to a QuoteRequest, so only a server that can't ask is decided now.
            if (!quote.legacy()) {
                return Settlement.LEFT_PENDING;
            }
            return switch (decide(quoted, quoting.actorId())) {
                case AUTOMATIC -> Settlement.ACCEPTED;
                case MANUAL -> Settlement.NEEDS_REVIEW;
                case DENIED, UNKNOWN -> Settlement.REJECTED;
            };
        }
        return quote.legacy() ? Settlement.ACCEPTED : Settlement.NEEDS_PROOF;
    }

    /**
     * Records how checking a remote quote turned out, if it's still waiting for that check, and still quotes what was
     * checked, with the same {@code QuoteAuthorization}.
     */
    public void verified(UUID statusId, URI quotedUri, URI approvalUri, boolean accepted) {
        transaction.executeWithoutResult(tx -> {
            var quote = quotes.findAndLock(statusId)
                    .filter(found -> found.state() == QuoteState.PENDING && found.quotedUri().equals(quotedUri)
                            && Objects.equals(found.approvalUri(), approvalUri));
            if (quote.isEmpty()) {
                return;
            }
            var found = quote.get();
            quotes.changeState(statusId, accepted ? QuoteState.ACCEPTED : QuoteState.REJECTED, found.legacy(), approvalUri,
                    null);
            if (accepted) {
                notifyLocalAuthor(found);
            }
        });
    }

    /**
     * How a {@code QuoteRequest} for a quote of a local status is answered.
     */
    public sealed interface RequestAnswer {

        /** @param stampId this server's {@code QuoteAuthorization} */
        record Accept(UUID stampId) implements RequestAnswer {
        }

        record Reject() implements RequestAnswer {
        }

        /** The quoted author hasn't decided yet, so nothing is sent. */
        record Wait() implements RequestAnswer {
        }
    }

    /**
     * Decides another server's request for approval of a quote of a local status, which the caller has found the policy
     * doesn't refuse, and whose quote is stored. A repeat of a request gets the answer it got before. A new request for a
     * quote that was rejected or revoked goes to the quoted author, whatever the policy says, so that asking again can't
     * undo what they decided.
     *
     * @param decision what the quoted status's policy says of the requester
     * @return a rejection if the quote isn't of this status
     */
    public RequestAnswer answerIncomingRequest(
            UUID quotingStatusId, UUID quotedStatusId, URI requestUri, QuoteDecision decision) {
        return transaction.execute(tx -> {
            // Held so that it can't be deleted before it's approved.
            if (statuses.findByIdAndLock(quotedStatusId).isEmpty()) {
                return new RequestAnswer.Reject();
            }
            var quote = quotes.findAndLock(quotingStatusId)
                    .filter(found -> quotedStatusId.equals(found.quotedStatusId()));
            if (quote.isEmpty()) {
                return new RequestAnswer.Reject();
            }
            var found = quote.get();
            var repeat = requestUri.equals(found.requestUri());
            return switch (found.state()) {
                case ACCEPTED -> found.stampId() != null
                        ? new RequestAnswer.Accept(found.stampId())
                        : new RequestAnswer.Accept(approve(found, requestUri));
                case REJECTED, REVOKED -> {
                    // As often as a local author can ask, so that the quoted author isn't asked over and over.
                    if (repeat || (found.requestedAt() != null
                            && found.requestedAt().plus(RETRY_AFTER).isAfter(Instant.now()))) {
                        yield new RequestAnswer.Reject();
                    }
                    putToReview(found, requestUri);
                    yield new RequestAnswer.Wait();
                }
                case PENDING -> {
                    if (found.isUnderReview()) {
                        if (!repeat) {
                            quotes.setRequest(quotingStatusId, requestUri);
                        }
                        yield new RequestAnswer.Wait();
                    }
                    if (decision == QuoteDecision.AUTOMATIC) {
                        yield new RequestAnswer.Accept(approve(found, requestUri));
                    }
                    putToReview(found, requestUri);
                    yield new RequestAnswer.Wait();
                }
            };
        });
    }

    /**
     * @param requestUri the {@code QuoteRequest} that asked for it, or null for a quote from a server that can't ask
     * @return the new {@code QuoteAuthorization}
     */
    private UUID approve(StatusQuote quote, URI requestUri) {
        var stamp = Ids.timeOrdered(Instant.now());
        quotes.changeState(quote.statusId(), QuoteState.ACCEPTED, requestUri == null && quote.legacy(), null, stamp);
        if (requestUri != null) {
            quotes.setRequest(quote.statusId(), requestUri);
        }
        if (quote.state() != QuoteState.ACCEPTED) {
            notifyLocalAuthor(quote);
        }
        return stamp;
    }

    /**
     * Only for a quote that isn't shown, so that nothing other servers have changes.
     */
    private void putToReview(StatusQuote quote, URI requestUri) {
        if (quote.state() == QuoteState.ACCEPTED) {
            throw new IllegalStateException("An accepted quote can't be put to review");
        }
        if (quote.state() != QuoteState.PENDING) {
            quotes.changeState(quote.statusId(), QuoteState.PENDING, quote.legacy(), null, null);
        }
        quotes.setRequested(quote.statusId(), requestUri);
        statuses.findById(quote.statusId()).ifPresent(quoting -> events.publishEvent(
                new QuoteReviewRequested(quoting.id(), quoting.actorId(), quote.quotedAccountId())));
    }

    /**
     * Puts a quote of a local status from a server that can't ask for approval to its author, if it's still waiting for
     * that check, and still quotes what was checked.
     */
    public void awaitReview(UUID statusId, URI quotedUri) {
        transaction.executeWithoutResult(tx -> quotes.findAndLock(statusId)
                .filter(found -> found.state() == QuoteState.PENDING && !found.isUnderReview()
                        && found.quotedUri().equals(quotedUri))
                .ifPresent(found -> putToReview(found, null)));
    }

    /**
     * Finds a quote of one of this author's statuses, for them to review, whatever state it's in.
     */
    public Optional<StatusQuote> findReview(UUID quotingStatusId, UUID ownerId) {
        return quotes.find(quotingStatusId)
                .filter(found -> ownerId.equals(found.quotedAccountId()) && found.quotedStatusId() != null)
                .filter(found -> statuses.findById(found.statusId()).isPresent());
    }

    /**
     * Lists the quotes of this author's statuses that wait for them to review, newest first.
     *
     * @param quotedStatusId only quotes of this status, or null for any
     * @return the quotes, which are each of a stored status
     */
    public List<StatusQuote> findReviews(UUID ownerId, UUID quotedStatusId, StatusPage page) {
        var ids = quotes.pageReviews(ownerId, quotedStatusId, page.maxId(), page.sinceId(), page.minId(), page.limit());
        var found = quotes.findAll(ids);
        return ids.stream().map(found::get).filter(Objects::nonNull).toList();
    }

    /**
     * Approves a quote of one of the author's statuses that waits for them to review, or that they rejected before, as
     * long as its author can still quote it.
     *
     * @return the quote as it is now, or empty if there's no such quote, or it can't be approved
     */
    public Optional<StatusQuote> authorizeReview(UUID quotingStatusId, UUID ownerId) {
        return transaction.execute(tx -> {
            var quoted = lockQuoted(quotingStatusId);
            var quote = quoted.flatMap(status -> lockQuote(quotingStatusId, status.id()))
                    .filter(found -> ownerId.equals(found.quotedAccountId()));
            if (quote.isEmpty()) {
                return Optional.<StatusQuote>empty();
            }
            var found = quote.get();
            if (found.state() == QuoteState.ACCEPTED) {
                return quote;
            }
            if (!found.isUnderReview() && found.state() != QuoteState.REJECTED) {
                return Optional.<StatusQuote>empty();
            }
            var quoting = statuses.findById(quotingStatusId);
            if (quoting.isEmpty() || decide(quoted.get(), quoting.get().actorId()) == QuoteDecision.DENIED) {
                return Optional.<StatusQuote>empty();
            }
            approve(found, null);
            events.publishEvent(new QuoteReviewed(quotingStatusId, quoting.get().actorId(), ownerId, true));
            noteChanged(quoting.get());
            return quotes.find(quotingStatusId);
        });
    }

    /**
     * Locks the status that a quote is of, which comes before locking the quote, as when answering a {@code QuoteRequest},
     * so that two changes to the same quote can't each hold the lock that the other waits for.
     */
    private Optional<Status> lockQuoted(UUID quotingStatusId) {
        return quotes.find(quotingStatusId).map(StatusQuote::quotedStatusId).flatMap(statuses::findByIdAndLock);
    }

    /**
     * @return the quote, locked, if it's still of this status
     */
    private Optional<StatusQuote> lockQuote(UUID quotingStatusId, UUID quotedStatusId) {
        return quotes.findAndLock(quotingStatusId).filter(found -> quotedStatusId.equals(found.quotedStatusId()));
    }

    /**
     * Rejects a quote of one of the author's statuses that waits for them to review. A repeat changes nothing.
     *
     * @return the quote as it is now, or empty if there's no such quote, or it isn't waiting
     */
    public Optional<StatusQuote> rejectReview(UUID quotingStatusId, UUID ownerId) {
        return transaction.execute(tx -> {
            var quote = quotes.findAndLock(quotingStatusId)
                    .filter(found -> ownerId.equals(found.quotedAccountId()) && found.quotedStatusId() != null);
            if (quote.isEmpty() || quote.get().state() == QuoteState.REJECTED) {
                return quote;
            }
            if (!quote.get().isUnderReview()) {
                return Optional.<StatusQuote>empty();
            }
            var found = quote.get();
            quotes.changeState(quotingStatusId, QuoteState.REJECTED, found.legacy(), null, null);
            statuses.findById(quotingStatusId).ifPresent(quoting ->
                    events.publishEvent(new QuoteReviewed(quotingStatusId, quoting.actorId(), ownerId, false)));
            return quotes.find(quotingStatusId);
        });
    }

    public enum Retry {
        ASKED,
        /** The author has no status with this ID that quotes something. */
        NOT_FOUND,
        ALREADY_ACCEPTED,
        /** Asked too recently, so that the quoted author isn't asked over and over. */
        TOO_SOON,
        /** The quoted status is gone, or its author doesn't allow this author to quote it any more. */
        NOT_ALLOWED
    }

    /**
     * How long a local author waits between asking for approval of the same quote.
     */
    public static final Duration RETRY_AFTER = Duration.ofHours(1);

    /**
     * Asks again for approval of one of the author's quotes that's waiting, or was rejected or revoked. A quote of a local
     * status is decided again by its policy. One of a remote status gets a new {@code QuoteRequest}, under a new ID, since a
     * server that answered the old one answers a repeat the same way.
     */
    public Retry askAgain(UUID statusId, UUID authorId) {
        return transaction.execute(tx -> {
            var quoting = statuses.findByIdAndLock(statusId).filter(found -> found.actorId().equals(authorId));
            if (quoting.isEmpty() || quotes.find(statusId).isEmpty()) {
                return Retry.NOT_FOUND;
            }
            var quoted = lockQuoted(statusId);
            var quote = quoted.isPresent() ? lockQuote(statusId, quoted.get().id()) : quotes.findAndLock(statusId);
            if (quote.isEmpty()) {
                return Retry.NOT_FOUND;
            }
            var found = quote.get();
            if (found.state() == QuoteState.ACCEPTED) {
                return Retry.ALREADY_ACCEPTED;
            }
            var now = Instant.now();
            if (found.requestedAt() != null && found.requestedAt().plus(RETRY_AFTER).isAfter(now)) {
                return Retry.TOO_SOON;
            }
            var quotedAuthor = Optional.ofNullable(found.quotedAccountId()).flatMap(actors::findById)
                    .filter(Actor::isActive);
            var decision = quoted.map(status -> decide(status, authorId)).orElse(QuoteDecision.DENIED);
            if (quotedAuthor.isEmpty() || decision == QuoteDecision.DENIED) {
                return Retry.NOT_ALLOWED;
            }
            var status = quoting.get();
            if (actors.isLocal(quotedAuthor.get())) {
                if (decision == QuoteDecision.AUTOMATIC) {
                    approve(found, null);
                    noteChanged(status);
                } else {
                    putToReview(found, null);
                }
                return Retry.ASKED;
            }
            // A quote of a status on a server that can't approve it is shown while it's asked, as it was at first.
            var legacy = quoted.get().quoteApproval() == null;
            quotes.changeState(statusId, legacy ? QuoteState.ACCEPTED : QuoteState.PENDING, legacy, null, null);
            quotes.setRequested(statusId, StatusQuote.requestUriFor(status.uri(), now));
            if (legacy) {
                noteChanged(status);
            }
            events.publishEvent(new QuoteRequested(statusId, authorId));
            return Retry.ASKED;
        });
    }

    /**
     * Records the quoted author's answer to a {@code QuoteRequest} that this server sent.
     *
     * @param signerUri   who answered, who has to be the quoted author
     * @param approvalUri their {@code QuoteAuthorization}, or null for a rejection
     * @return whether the request was this server's, to this author
     */
    public boolean answerRequest(URI requestUri, URI signerUri, URI approvalUri) {
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            var quote = quotes.findByRequestAndLock(requestUri);
            var quoting = quote.flatMap(found -> statuses.findById(found.statusId()))
                    .filter(status -> actors.isLocal(status.actorId()));
            var quotedAuthor = quote.map(StatusQuote::quotedAccountId).flatMap(actors::findById).map(Actor::uri);
            if (quoting.isEmpty() || !quotedAuthor.equals(Optional.of(signerUri))) {
                return false;
            }
            var found = quote.get();
            // A rejection can be followed by an approval, when the quoted author changes their mind.
            var answerable = found.state() == QuoteState.PENDING
                    || (found.state() == QuoteState.ACCEPTED && found.legacy())
                    || (found.state() == QuoteState.REJECTED && approvalUri != null);
            if (!answerable) {
                return true;
            }
            quotes.changeState(found.statusId(), approvalUri == null ? QuoteState.REJECTED : QuoteState.ACCEPTED, false,
                    approvalUri, null);
            // A rejection only changes what other servers have if they were showing it.
            if (approvalUri != null || found.state() == QuoteState.ACCEPTED) {
                noteChanged(quoting.get());
            }
            return true;
        }));
    }

    /**
     * Whether this is the ID of a {@code QuoteRequest} that was sent, or received, for a quote stored here.
     */
    public boolean isOwnRequest(URI requestUri) {
        return quotes.isKnownRequest(requestUri);
    }

    /**
     * Withdraws the quotes that another server approved with this {@code QuoteAuthorization}, now that its author has
     * deleted it.
     *
     * @param actorUri who deleted it, who has to be the quoted author
     */
    public void revokeApproval(URI approvalUri, URI actorUri) {
        transaction.executeWithoutResult(tx -> {
            for (var quote : quotes.findByApprovalAndLock(approvalUri)) {
                var author = Optional.ofNullable(quote.quotedAccountId()).flatMap(actors::findById).map(Actor::uri);
                if (!author.equals(Optional.of(actorUri))) {
                    continue;
                }
                if (quote.state() == QuoteState.ACCEPTED || quote.state() == QuoteState.PENDING) {
                    quotes.changeState(quote.statusId(), QuoteState.REVOKED, quote.legacy(), null, null);
                }
                statuses.findById(quote.statusId()).ifPresent(this::noteChanged);
            }
        });
    }

    /**
     * Whether some quote stored here was approved with this {@code QuoteAuthorization}, which is public in the quote's
     * {@code Note}, so saying so tells nobody anything.
     */
    public boolean isKnownApproval(URI approvalUri) {
        return quotes.isKnownApproval(approvalUri);
    }

    /**
     * Whether the quoted author deleted this {@code QuoteAuthorization} before the quote that names it arrived.
     */
    public boolean wasApprovalDeleted(URI approvalUri, URI quotedAuthorUri) {
        return statuses.isRemoteDeleted(approvalUri, quotedAuthorUri);
    }

    /**
     * Applies what another server's {@code Update} says about a stored status's quote and who can quote it. This doesn't
     * depend on its {@code updated}, since a quote is checked with its approver, whatever order {@code Update}s run in. A
     * quote of a local status is decided by this server's record, which an {@code Update} can't change.
     */
    public void reconcileRemote(URI statusUri, UUID authorId, RemoteQuote quote, QuoteApproval policy) {
        transaction.executeWithoutResult(tx -> {
            var status = statuses.findByUriForAuthor(statusUri, authorId)
                    .filter(found -> !actors.isLocal(found.actorId()));
            if (status.isEmpty()) {
                return;
            }
            var id = status.get().id();
            if (!Objects.equals(policy, status.get().quoteApproval())) {
                quotes.setPolicy(id, policy);
            }
            var current = quotes.findAndLock(id);
            if (quote == null) {
                current.ifPresent(found -> quotes.delete(id));
                return;
            }
            if (current.isEmpty()) {
                statusService.insertRemoteQuote(id, quote, true);
                return;
            }
            var found = current.get();
            var sameTarget = found.quotedUri().equals(quote.quotedUri());
            if (sameTarget && Objects.equals(found.approvalUri(), quote.authorizationUri())
                    && found.legacy() == quote.legacy()) {
                return;
            }
            if (sameTarget && found.quotedAccountId() != null && actors.isLocal(found.quotedAccountId())) {
                return;
            }
            var quoted = sameTarget && found.quotedStatusId() != null
                    ? Optional.of(new StatusRepository.Parent(found.quotedStatusId(), found.quotedAccountId()))
                    : statuses.findParentByUriAndLock(quote.quotedUri());
            quotes.replace(id, quote.quotedUri(), quoted, quote.legacy(), quote.authorizationUri());
            events.publishEvent(new QuoteVerificationNeeded(id, true));
        });
    }

    private void notifyLocalAuthor(StatusQuote quote) {
        if (quote.quotedStatusId() == null || quote.quotedAccountId() == null || !actors.isLocal(quote.quotedAccountId())) {
            return;
        }
        statuses.findById(quote.statusId())
                .ifPresent(quoting -> statusService.publishQuoteAccepted(quoting, quote.quotedAccountId()));
    }

    /**
     * Tells other servers that a local status's {@code Note} has changed. A remote one isn't this server's to send.
     */
    private void noteChanged(Status status) {
        if (actors.isLocal(status.actorId())) {
            statusService.touchNote(status.id(), status.actorId());
        }
    }
}
