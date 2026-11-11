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
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
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

    private static final Set<QuoteState> UNANSWERED = EnumSet.of(QuoteState.PENDING);

    private final QuoteRepository quotes;
    private final StatusRepository statuses;
    private final StatusService statusService;
    private final ActorService actors;
    private final QuoteRules rules;
    private final FollowService follows;
    private final BlockService blocks;
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
            ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager) {
        this.quotes = quotes;
        this.statuses = statuses;
        this.statusService = statusService;
        this.actors = actors;
        this.rules = rules;
        this.follows = follows;
        this.blocks = blocks;
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
     * @return an entry for each status that has a policy, which those from before quotes don't, and nor do remote ones
     * whose server didn't give one
     */
    public Map<UUID, QuoteApproval> policies(Collection<UUID> statusIds) {
        return quotes.policies(statusIds);
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
        return quotes.findNoteUpdatedAt(statusId);
    }

    /**
     * What would happen to a quote of this status by this account.
     */
    public QuoteDecision decide(Status quoted, UUID quoter) {
        var local = actors.findById(quoted.actorId()).map(actors::isLocal).orElse(false);
        return rules.decide(quoted, quotes.policies(List.of(quoted.id())).get(quoted.id()), local, quoter);
    }

    /**
     * Like {@link #decide}, for each of these statuses and one viewer, with one lookup of each kind.
     *
     * @param localAuthors which of the statuses' authors are this server's
     * @return an entry for each status
     */
    public Map<UUID, QuoteDecision> decide(
            Collection<Status> quoted, Map<UUID, QuoteApproval> policies, Set<UUID> localAuthors, Optional<UUID> viewer) {
        var authors = quoted.stream().map(Status::actorId).collect(Collectors.toSet());
        Set<UUID> followed = viewer.map(id -> Set.copyOf(follows.followedIds(id))).orElse(Set.of());
        Set<UUID> refusing = viewer.map(id -> blocks.refusing(id, authors)).orElse(Set.of());
        var decisions = new HashMap<UUID, QuoteDecision>();
        for (var status : quoted) {
            decisions.put(status.id(), QuoteRules.decide(status, policies.get(status.id()),
                    localAuthors.contains(status.actorId()), viewer,
                    (follower, followedId) -> viewer.filter(follower::equals).isPresent()
                            ? followed.contains(followedId) : follows.isFollowing(follower, followedId),
                    (author, quoter) -> refusing.contains(author)));
        }
        return decisions;
    }

    /**
     * Lists the accepted quotes of a status that this viewer can see, newest first.
     */
    public List<Status> findAccepted(UUID quotedStatusId, Optional<UUID> viewer, StatusPage page) {
        var ids = quotes.pageAccepted(quotedStatusId, page.maxId(), page.minId() != null ? page.minId() : page.sinceId(),
                page.limit());
        var found = viewer.isPresent() ? statusService.findVisible(ids, viewer.get())
                : statusService.findVisibleToAnyone(ids);
        return found.stream().sorted(Comparator.comparing(Status::id).reversed()).toList();
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
     * Finds a {@code QuoteAuthorization} that this server gave, while it's still in force.
     */
    public Optional<StatusQuote> findStamp(UUID stampId) {
        return quotes.findByStamp(stampId).filter(quote -> quote.state() == QuoteState.ACCEPTED);
    }

    /**
     * What {@link #revoke} did.
     */
    public enum Revocation {
        REVOKED,
        /** The status, or a quote of it by that status, can't be found. */
        NOT_FOUND,
        /** The status isn't the account's. */
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
            var found = quote.get();
            if (found.state() == QuoteState.REVOKED) {
                return Revocation.REVOKED;
            }
            var quoting = statuses.findById(quotingStatusId);
            quotes.changeState(quotingStatusId, EnumSet.complementOf(EnumSet.of(QuoteState.REVOKED)), QuoteState.REVOKED,
                    found.legacy(), null, found.stampId());
            quoting.ifPresent(status -> {
                events.publishEvent(new QuoteRevoked(
                        quotingStatusId, status.actorId(), quotedStatusId, ownerId,
                        found.state() == QuoteState.ACCEPTED ? found.stampId() : null));
                noteChanged(status);
            });
            return Revocation.REVOKED;
        });
    }

    /**
     * Changes who can quote one of the author's statuses. It doesn't change what's been approved already, as on Mastodon.
     * A followers-only or direct status can only be quoted by its author, whatever it's set to.
     *
     * @return the status, or empty if the author has no status with this ID
     */
    public Optional<Status> setPolicy(UUID statusId, UUID authorId, QuotePolicy policy) {
        return transaction.execute(tx -> {
            var status = statuses.findByIdForEdit(statusId)
                    .filter(found -> found.actorId().equals(authorId) && !found.isReblog());
            if (status.isEmpty() || status.get().visibility() == Visibility.PRIVATE
                    || status.get().visibility() == Visibility.DIRECT) {
                return status;
            }
            var approval = policy.approval();
            if (!approval.equals(quotes.policies(List.of(statusId)).get(statusId))) {
                quotes.setPolicy(statusId, approval);
                noteChanged(status.get());
            }
            return status;
        });
    }

    /**
     * How checking a remote quote turned out.
     */
    public enum Verification {
        /** It's approved, its author's own, or needs no approval. */
        ACCEPTED,
        /** Its author's server can't ask for approval, and this server's author wouldn't have given it. */
        REJECTED
    }

    /**
     * Records how checking a remote quote turned out, if it's still waiting for that check, and still quotes what was
     * checked, with the same {@code QuoteAuthorization}.
     *
     * @return whether it was changed
     */
    public boolean verified(UUID statusId, URI quotedUri, URI approvalUri, Verification verification) {
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            var quote = quotes.findAndLock(statusId)
                    .filter(found -> found.state() == QuoteState.PENDING && found.quotedUri().equals(quotedUri)
                            && Objects.equals(found.approvalUri(), approvalUri));
            if (quote.isEmpty()) {
                return false;
            }
            var found = quote.get();
            var accepted = verification == Verification.ACCEPTED;
            quotes.changeState(statusId, UNANSWERED, accepted ? QuoteState.ACCEPTED : QuoteState.REJECTED, found.legacy(),
                    approvalUri, null);
            if (accepted) {
                notifyLocalAuthor(found);
            }
            return true;
        }));
    }

    /**
     * Approves another server's quote of a local status, which its author asked for with a {@code QuoteRequest}, and which
     * the caller has decided to approve. A repeat gives the same {@code QuoteAuthorization}.
     *
     * @return this server's {@code QuoteAuthorization}, or empty if the quote isn't of this status, or was revoked or
     * rejected
     */
    public Optional<UUID> approveRequest(UUID quotingStatusId, UUID quotedStatusId, URI requestUri) {
        return transaction.execute(tx -> {
            // Held so that it can't be deleted before it's approved.
            if (statuses.findByIdAndLock(quotedStatusId).isEmpty()) {
                return Optional.<UUID>empty();
            }
            var quote = quotes.findAndLock(quotingStatusId)
                    .filter(found -> quotedStatusId.equals(found.quotedStatusId()));
            if (quote.isEmpty()) {
                return Optional.<UUID>empty();
            }
            var found = quote.get();
            if (found.state() == QuoteState.ACCEPTED && found.stampId() != null) {
                return Optional.of(found.stampId());
            }
            var approvable = found.state() == QuoteState.PENDING
                    || (found.state() == QuoteState.ACCEPTED && found.legacy());
            if (!approvable) {
                return Optional.<UUID>empty();
            }
            var stamp = Ids.timeOrdered(Instant.now());
            quotes.changeState(quotingStatusId, EnumSet.of(found.state()), QuoteState.ACCEPTED, false, null, stamp);
            quotes.setRequest(quotingStatusId, requestUri);
            if (found.state() != QuoteState.ACCEPTED) {
                notifyLocalAuthor(found);
            }
            return Optional.of(stamp);
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
                    .filter(status -> actors.findById(status.actorId()).map(actors::isLocal).orElse(false));
            var quotedAuthor = quote.map(StatusQuote::quotedAccountId).flatMap(actors::findById).map(Actor::uri);
            if (quoting.isEmpty() || !quotedAuthor.equals(Optional.of(signerUri))) {
                return false;
            }
            var found = quote.get();
            var answerable = found.state() == QuoteState.PENDING
                    || (found.state() == QuoteState.ACCEPTED && found.legacy());
            if (!answerable) {
                return true;
            }
            quotes.changeState(found.statusId(), EnumSet.of(found.state()),
                    approvalUri == null ? QuoteState.REJECTED : QuoteState.ACCEPTED, false, approvalUri, null);
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
     * @return whether any quote was approved with it
     */
    public boolean revokeApproval(URI approvalUri, URI actorUri) {
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            var revoked = false;
            for (var quote : quotes.findByApprovalAndLock(approvalUri)) {
                var author = Optional.ofNullable(quote.quotedAccountId()).flatMap(actors::findById).map(Actor::uri);
                if (!author.equals(Optional.of(actorUri))) {
                    continue;
                }
                revoked = true;
                quotes.changeState(quote.statusId(), EnumSet.of(QuoteState.ACCEPTED, QuoteState.PENDING),
                        QuoteState.REVOKED, quote.legacy(), null, null);
                statuses.findById(quote.statusId())
                        .filter(status -> actors.findById(status.actorId()).map(actors::isLocal).orElse(false))
                        .ifPresent(this::noteChanged);
            }
            return revoked;
        }));
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
        return quotes.wasApprovalDeleted(approvalUri, quotedAuthorUri);
    }

    /**
     * Applies what another server's {@code Update} says about a stored status's quote and who can quote it. This doesn't
     * depend on its {@code updated}, since a quote is checked with its approver, whatever order {@code Update}s run in. A
     * quote of a local status is decided by this server's record, which an {@code Update} can't change.
     */
    public void reconcileRemote(URI statusUri, UUID authorId, RemoteQuote quote, QuoteApproval policy) {
        transaction.executeWithoutResult(tx -> {
            var status = statuses.findByUriForAuthor(statusUri, authorId)
                    .filter(found -> actors.findById(found.actorId()).filter(actor -> !actors.isLocal(actor)).isPresent());
            if (status.isEmpty()) {
                return;
            }
            var id = status.get().id();
            if (!Objects.equals(policy, quotes.policies(List.of(id)).get(id))) {
                quotes.setPolicy(id, policy);
            }
            var current = quotes.findAndLock(id);
            if (quote == null) {
                current.ifPresent(found -> quotes.delete(id));
                return;
            }
            if (current.isEmpty()) {
                var quoted = statuses.findParentByUriAndLock(quote.quotedUri());
                quotes.insert(id, quote.quotedUri(), quoted.map(StatusRepository.Parent::id).orElse(null),
                        quoted.map(StatusRepository.Parent::actorId).orElse(null), QuoteState.PENDING, quote.legacy(),
                        quote.authorizationUri(), null, null);
                events.publishEvent(new QuoteVerificationNeeded(id, true));
                return;
            }
            var found = current.get();
            var sameTarget = found.quotedUri().equals(quote.quotedUri());
            if (sameTarget && Objects.equals(found.approvalUri(), quote.authorizationUri())
                    && found.legacy() == quote.legacy()) {
                return;
            }
            if (sameTarget && isLocal(found.quotedAccountId())) {
                return;
            }
            var quoted = sameTarget && found.quotedStatusId() != null
                    ? Optional.of(new StatusRepository.Parent(found.quotedStatusId(), found.quotedAccountId()))
                    : statuses.findParentByUriAndLock(quote.quotedUri());
            quotes.replace(id, quote.quotedUri(), quoted.map(StatusRepository.Parent::id).orElse(null),
                    quoted.map(StatusRepository.Parent::actorId).orElse(null), quote.legacy(), quote.authorizationUri());
            events.publishEvent(new QuoteVerificationNeeded(id, true));
        });
    }

    private boolean isLocal(UUID actorId) {
        return actorId != null && actors.findById(actorId).map(actors::isLocal).orElse(false);
    }

    private void notifyLocalAuthor(StatusQuote quote) {
        if (quote.quotedStatusId() == null || !isLocal(quote.quotedAccountId())) {
            return;
        }
        statuses.findById(quote.statusId())
                .filter(quoting -> !quoting.actorId().equals(quote.quotedAccountId()))
                .ifPresent(quoting -> events.publishEvent(new QuoteAccepted(
                        quoting.id(), quoting.actorId(), quote.quotedStatusId(), quote.quotedAccountId())));
    }

    /**
     * Tells other servers that a local status's {@code Note} has changed. A remote one isn't this server's to send.
     */
    private void noteChanged(Status status) {
        if (!isLocal(status.actorId())) {
            return;
        }
        quotes.touchNote(status.id())
                .ifPresent(at -> events.publishEvent(new StatusNoteChanged(status.id(), status.actorId(), at)));
    }
}
