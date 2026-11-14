package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.BoostStats;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.LikeStats;
import dev.biribiri.elysium.content.MediaAttachment;
import dev.biribiri.elysium.content.MediaAttachments;
import dev.biribiri.elysium.content.PollService;
import dev.biribiri.elysium.content.StatusPage;
import dev.biribiri.elysium.content.StatusPinService;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.StatusVersion;
import dev.biribiri.elysium.content.QuoteApproval;
import dev.biribiri.elysium.content.QuoteDecision;
import dev.biribiri.elysium.content.QuoteService;
import dev.biribiri.elysium.content.QuoteState;
import dev.biribiri.elysium.content.StatusQuote;
import dev.biribiri.elysium.graph.BlockService;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Builds status responses, loading the accounts they embed together rather than one query per status.
 */
@Component
class StatusResponses {

    private final ActorService actors;
    private final AccountResponses accounts;
    private final InstanceProperties instance;
    private final StatusService statuses;
    private final LikeService likes;
    private final BoostService boosts;
    private final MediaAttachments attachments;
    private final QuoteService quotes;
    private final BlockService blocks;
    private final PollService polls;
    private final StatusPinService pins;

    StatusResponses(
            ActorService actors, AccountResponses accounts, InstanceProperties instance,
            StatusService statuses, LikeService likes, BoostService boosts, MediaAttachments attachments,
            QuoteService quotes, BlockService blocks, PollService polls, StatusPinService pins) {
        this.polls = polls;
        this.pins = pins;
        this.quotes = quotes;
        this.blocks = blocks;
        this.attachments = attachments;
        this.likes = likes;
        this.boosts = boosts;
        this.actors = actors;
        this.statuses = statuses;
        this.accounts = accounts;
        this.instance = instance;
    }

    /**
     * @throws RecordNotFoundException if the status's author can't be loaded
     */
    StatusResponse one(Status status, Optional<UUID> viewer) {
        return only(build(List.of(status), false, viewer, Map.of()));
    }

    /**
     * @throws RecordNotFoundException if the status's author can't be loaded
     */
    StatusResponse oneWithText(Status status, Optional<UUID> viewer) {
        return only(build(List.of(status), true, viewer, Map.of()));
    }

    /**
     * For a status that's just been deleted, whose attachments can't be found by it any more.
     *
     * @param media its attachments, from before it was deleted
     */
    StatusResponse deleted(Status status, Optional<UUID> viewer, List<MediaAttachment> media) {
        return only(build(List.of(status), true, viewer, Map.of(status.id(), media)));
    }

    /**
     * @return a status's attachments, for {@link #deleted}
     */
    List<MediaAttachment> attachmentsOf(UUID statusId) {
        return attachments.findByStatuses(List.of(statusId)).getOrDefault(statusId, List.of());
    }

    /**
     * Leaves out statuses whose author can't be loaded, like {@link #page}.
     */
    List<StatusResponse> list(List<Status> found, Optional<UUID> viewer) {
        return build(found, false, viewer, Map.of());
    }

    /**
     * @throws RecordNotFoundException if the status's author can't be loaded
     */
    List<StatusEditResponse> history(Status status, List<StatusVersion> versions) {
        var account = actors.findById(status.actorId())
                .filter(Actor::isActive)
                .map(accounts::one)
                .orElseThrow(RecordNotFoundException::new);
        var media = attachments.findByStatuses(List.of(status.id())).getOrDefault(status.id(), List.of()).stream()
                .map(attachment -> MediaAttachmentResponse.from(attachment, instance))
                .toList();
        var last = versions.size() - 1;
        return IntStream.range(0, versions.size())
                .mapToObj(i -> StatusEditResponse.from(versions.get(i), account, i == last ? media : List.of()))
                .toList();
    }

    private static StatusResponse only(List<StatusResponse> built) {
        return built.stream().findFirst().orElseThrow(RecordNotFoundException::new);
    }

    /**
     * Builds a page of statuses, with a {@code Link} header pointing to the pages around it. The links come from every
     * status found, so paging still works if {@link #build} leaves some out.
     *
     * @param filters the request's parameters to repeat in those links, leaving out the page cursors
     */
    ResponseEntity<List<StatusResponse>> page(
            String path, Map<String, ?> filters, StatusPage page, List<Status> found, Optional<UUID> viewer) {
        var response = ResponseEntity.ok();
        PageLinks.addTo(response, instance, path, filters, page.limit(), found.stream().map(Status::id).toList());
        return response.body(build(found, false, viewer, Map.of()));
    }

    /**
     * Leaves out statuses whose author can't be loaded, such as when the account was suspended or deleted after the
     * statuses were read, so that one of them doesn't fail the whole response. A boost is left out the same way when the
     * status it boosts can't be shown to the viewer.
     */
    /**
     * @param knownMedia attachments to use for statuses that can't be found by them any more
     */
    private List<StatusResponse> build(
            List<Status> found, boolean includeText, Optional<UUID> viewer, Map<UUID, List<MediaAttachment>> knownMedia) {
        var originalIds = found.stream().filter(Status::isReblog).map(Status::reblogOfId).distinct().toList();
        var originals = visible(originalIds, viewer).stream()
                .collect(Collectors.toMap(Status::id, Function.identity()));
        var shown = Stream.concat(found.stream(), originals.values().stream()).filter(status -> !status.isReblog())
                .map(Status::id).distinct().toList();
        var quoteRows = new HashMap<>(quotes.findAll(shown));
        var quotedIds = quoteRows.values().stream().map(StatusQuote::quotedStatusId).filter(Objects::nonNull)
                .distinct().toList();
        var quoted = visible(quotedIds, viewer).stream().collect(Collectors.toMap(Status::id, Function.identity()));
        quoteRows.putAll(quotes.findAll(quoted.keySet()));
        var all = Stream.of(found.stream(), originals.values().stream(), quoted.values().stream()).flatMap(Function.identity())
                .toList();

        var authors = actors.findByIds(all.stream().map(Status::actorId).collect(Collectors.toSet())).stream()
                .filter(Actor::isActive)
                .collect(Collectors.toMap(Actor::id, Function.identity()));
        var accountResponses = accounts.byId(authors.values());
        var ids = all.stream().map(Status::id).distinct().toList();
        var mentions = statuses.findMentions(ids);
        var tags = statuses.findTags(ids);
        var media = new HashMap<>(attachments.findByStatuses(ids));
        media.putAll(knownMedia);
        var likeStats = likes.stats(ids, viewer);
        var boostStats = boosts.stats(ids, viewer);
        var quoteCounts = quotes.acceptedCounts(ids);
        var pollsById = polls.findAll(ids, viewer);
        var pinned = pins.pinnedIds(all, viewer);
        var now = Instant.now();
        var localAuthors = authors.values().stream().filter(actors::isLocal).map(Actor::id).collect(Collectors.toSet());
        var decisions = quotes.decide(all.stream().filter(status -> !status.isReblog()).distinct().toList(), localAuthors, viewer);
        Map<UUID, BlockService.Hiding> hiding = viewer.isEmpty() ? Map.of() : blocks.hidingReasons(viewer.get(),
                quoted.values().stream().map(Status::actorId).collect(Collectors.toSet()));
        Function<Status, StatusResponse.QuoteApprovalResponse> approval = status -> approval(
                status, status.quoteApproval(), localAuthors.contains(status.actorId()), decisions.get(status.id()));
        StatusBuilder basic = (status, reblog, quote) -> StatusResponse.from(
                status,
                accountResponses.get(status.actorId()),
                mentions.getOrDefault(status.id(), List.of()).stream()
                        .map(actor -> MentionResponse.from(actor, actors.isLocal(actor)))
                        .toList(),
                tags.getOrDefault(status.id(), List.of()).stream()
                        .map(name -> StatusTagResponse.of(name, instance))
                        .toList(),
                media.getOrDefault(status.id(), List.of()).stream()
                        .map(attachment -> MediaAttachmentResponse.from(attachment, instance))
                        .toList(),
                includeText,
                status.isReblog() ? LikeStats.EMPTY : likeStats.getOrDefault(status.id(), LikeStats.EMPTY),
                status.isReblog() ? BoostStats.EMPTY : boostStats.getOrDefault(status.id(), BoostStats.EMPTY),
                reblog,
                quote,
                approval.apply(status),
                status.isReblog() ? 0 : quoteCounts.getOrDefault(status.id(), 0L),
                Optional.ofNullable(pollsById.get(status.id())).map(poll -> PollResponse.from(poll, now)).orElse(null),
                pinned.contains(status.id()));
        // A status that's quoted only shows what it quotes in turn by ID, as on Mastodon, so that quotes don't nest.
        Function<Status, StatusResponse> nested = status -> basic.apply(status, null,
                Optional.ofNullable(quoteRows.get(status.id())).map(StatusResponses::shallow).orElse(null));
        Function<Status, StatusResponse> full = status -> basic.apply(status, null,
                Optional.ofNullable(quoteRows.get(status.id())).map(row -> {
                    var target = Optional.ofNullable(row.quotedStatusId()).map(quoted::get)
                            .filter(candidate -> authors.containsKey(candidate.actorId()));
                    var state = state(row, target.isPresent(), target.map(Status::actorId).map(hiding::get));
                    return new StatusResponse.QuoteResponse(state,
                            SHOWS_QUOTED.contains(state) ? target.map(nested).orElse(null) : null);
                }).orElse(null));
        return found.stream()
                .filter(status -> authors.containsKey(status.actorId()))
                .flatMap(status -> {
                    if (!status.isReblog()) {
                        return Stream.of(full.apply(status));
                    }
                    var original = originals.get(status.reblogOfId());
                    if (original == null || !authors.containsKey(original.actorId())) {
                        return Stream.empty();
                    }
                    return Stream.of(basic.apply(status, full.apply(original), null));
                })
                .toList();
    }

    @FunctionalInterface
    private interface StatusBuilder {
        StatusResponse apply(Status status, StatusResponse reblog, Object quote);
    }

    private List<Status> visible(List<UUID> ids, Optional<UUID> viewer) {
        return viewer.isPresent() ? statuses.findVisible(ids, viewer.get()) : statuses.findVisibleToAnyone(ids);
    }

    /**
     * The states in which Mastodon includes the quoted status, which a client hides behind a warning for the last three.
     */
    private static final Set<String> SHOWS_QUOTED = Set.of("accepted", "blocked_account", "blocked_domain", "muted_account");

    /**
     * @param visible whether the viewer can see the quoted status
     * @param hiding  why the viewer has hidden its author, if they have
     */
    private static String state(StatusQuote quote, boolean visible, Optional<BlockService.Hiding> hiding) {
        if (quote.state() != QuoteState.ACCEPTED) {
            return quote.state().value();
        }
        if (quote.isOfDeleted()) {
            return "deleted";
        }
        if (quote.quotedStatusId() == null) {
            return "pending";
        }
        if (!visible) {
            return "unauthorized";
        }
        return hiding.map(reason -> switch (reason) {
            case BLOCKED -> "blocked_account";
            case DOMAIN_BLOCKED -> "blocked_domain";
            case MUTED -> "muted_account";
        }).orElse("accepted");
    }

    private static StatusResponse.ShallowQuoteResponse shallow(StatusQuote quote) {
        var state = state(quote, true, Optional.empty());
        return new StatusResponse.ShallowQuoteResponse(state,
                state.equals("accepted") ? quote.quotedStatusId().toString() : null);
    }

    /**
     * Mastodon's QuoteApproval. A status from another server that has no policy is from one that can't be asked, which
     * anyone here can quote, so the audience is one that Mastodon's clients don't know.
     */
    private static StatusResponse.QuoteApprovalResponse approval(
            Status status, QuoteApproval policy, boolean local, QuoteDecision decision) {
        if (status.isReblog()) {
            return new StatusResponse.QuoteApprovalResponse(List.of(), List.of(), QuoteDecision.DENIED.value());
        }
        if (policy == null) {
            return new StatusResponse.QuoteApprovalResponse(local ? List.of() : List.of("unsupported_policy"), List.of(),
                    decision.value());
        }
        return new StatusResponse.QuoteApprovalResponse(audiences(policy.automatic()), audiences(policy.manual()),
                decision.value());
    }

    private static List<String> audiences(Set<QuoteApproval.Audience> audiences) {
        return audiences.stream()
                .map(audience -> audience == QuoteApproval.Audience.UNSUPPORTED ? "unsupported_policy" : audience.value())
                .sorted()
                .toList();
    }
}
