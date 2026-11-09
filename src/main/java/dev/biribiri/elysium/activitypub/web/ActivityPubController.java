package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.outbound.Notes;
import dev.biribiri.elysium.activitypub.vocab.Application;
import dev.biribiri.elysium.activitypub.vocab.Create;
import dev.biribiri.elysium.activitypub.vocab.Note;
import dev.biribiri.elysium.activitypub.vocab.OrderedCollection;
import dev.biribiri.elysium.activitypub.vocab.OrderedCollectionPage;
import dev.biribiri.elysium.activitypub.vocab.Person;
import dev.biribiri.elysium.activitypub.vocab.Tombstone;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.actor.InstanceActor;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.activitypub.vocab.Announce;
import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusBoost;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.StatusLike;
import dev.biribiri.elysium.content.StatusPage;
import dev.biribiri.elysium.activitypub.vocab.Like;
import dev.biribiri.elysium.graph.FollowEntry;
import dev.biribiri.elysium.graph.FollowPage;
import dev.biribiri.elysium.graph.FollowService;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.ACTIVITY_JSON;
import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.ANY_LD_JSON;
import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.LD_JSON;

@RestController
@Tag(name = FederationDocs.ACTIVITYPUB_TAG, description = """
        ActivityPub documents and inboxes, for other servers. `FEDERATION.md` in the repository describes them in full.""",
        externalDocs = @ExternalDocumentation(url = "https://www.w3.org/TR/activitypub/"))
public class ActivityPubController {

    /**
     * For documents that don't read the signature yet, which are served as to an anonymous viewer.
     */
    private static final Optional<UUID> ANONYMOUS = Optional.empty();

    static final int COLLECTION_PAGE_SIZE = 40;

    private final ActorService actors;
    private final StatusService statuses;
    private final LikeService likes;
    private final BoostService boosts;
    private final FollowService follows;
    private final Notes notes;
    private final InstanceProperties instance;
    private final ProfileService profiles;
    private final FetchSigners signers;

    ActivityPubController(
            ActorService actors,
            StatusService statuses,
            LikeService likes,
            BoostService boosts,
            FollowService follows,
            Notes notes,
            InstanceProperties instance,
            ProfileService profiles,
            FetchSigners signers) {
        this.signers = signers;
        this.instance = instance;
        this.profiles = profiles;
        this.actors = actors;
        this.statuses = statuses;
        this.likes = likes;
        this.boosts = boosts;
        this.follows = follows;
        this.notes = notes;
    }

    @GetMapping(path = "/users/{username}", produces = {ACTIVITY_JSON, LD_JSON, ANY_LD_JSON})
    @Operation(
            operationId = "getActor",
            summary = "Fetch a user's actor document",
            description = """
                    A `Person`, with an RSA 2048 `publicKey` identified as `{actor-uri}#main-key`, and the shared inbox \
                    at `/inbox` as `endpoints.sharedInbox`. The profile is in `name`, `summary` (HTML), `icon` and \
                    `image` (WebP `Image`s), and `attachment` (`PropertyValue`s), each left out if it's empty. A \
                    suspended user's document has `suspended: true`, the same key, and no profile.\s""" + FederationDocs.NEGOTIATED,
            parameters = @Parameter(name = "username", description = FederationDocs.USERNAME),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The actor, or the profile page for HTML.",
                            content = {
                            @Content(mediaType = ACTIVITY_JSON, schema = @Schema(implementation = Person.class)),
                            @Content(mediaType = MediaType.TEXT_HTML_VALUE, schema = @Schema(type = "string"))
                    }),
                    @ApiResponse(responseCode = "404", description = "There's no such user."),
                    @ApiResponse(responseCode = "406", description = "The `Accept` header allows neither type."),
                    @ApiResponse(responseCode = "410", description = """
                            The user was deleted, with a `Tombstone` whose `formerType` is `Person`.""", content = {
                            @Content(mediaType = ACTIVITY_JSON, schema = @Schema(implementation = Tombstone.class)),
                    })
            })
    ResponseEntity<?> actor(@PathVariable String username) {
        var actor = actors.findAnyLocalByUsername(username);
        if (actor.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (actor.get().isDeleted()) {
            return ResponseEntity.status(HttpStatus.GONE).body(Tombstone.from(actor.get()));
        }
        // Not an error, because other servers take any answer that isn't this document as the actor being gone or broken.
        return ResponseEntity.ok(actor.get().isSuspended()
                ? Person.suspended(actor.get(), instance)
                : Person.from(actor.get(), profiles.find(actor.get().id()), instance));
    }

    /**
     * There's no web page at this URL.
     */
    @CrossOrigin
    @GetMapping(path = "/users/{username}/followers", produces = {ACTIVITY_JSON, LD_JSON, ANY_LD_JSON})
    @Operation(
            operationId = "getFollowers",
            summary = "Fetch a user's followers",
            description = FederationDocs.COLLECTION,
            parameters = {
                    @Parameter(name = "username", description = FederationDocs.USERNAME),
                    @Parameter(name = "page", description = "Any value asks for a page rather than the collection."),
                    @Parameter(name = "max_id", description = "The cursor from a `next` link.")
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The collection or the page.", content = {
                            @Content(mediaType = ACTIVITY_JSON, schema = @Schema(oneOf = {
                                    OrderedCollection.class, OrderedCollectionPage.class}))
                    }),
                    @ApiResponse(responseCode = "400", description = "`max_id` isn't valid."),
                    @ApiResponse(responseCode = "403", description = "The user is suspended."),
                    @ApiResponse(responseCode = "404", description = "There's no such user."),
                    @ApiResponse(responseCode = "406", description = "The `Accept` header doesn't allow JSON."),
                    @ApiResponse(responseCode = "410", description = "The user was deleted.")
            })
    ResponseEntity<?> followers(
            @PathVariable String username,
            @RequestParam(required = false) String page,
            @RequestParam(name = "max_id", required = false) String maxId) {
        return collection(username, page, maxId, true);
    }

    /**
     * Like {@link #followers}.
     */
    @CrossOrigin
    @GetMapping(path = "/users/{username}/following", produces = {ACTIVITY_JSON, LD_JSON, ANY_LD_JSON})
    @Operation(
            operationId = "getFollowing",
            summary = "Fetch the actors a user follows",
            description = FederationDocs.COLLECTION,
            parameters = {
                    @Parameter(name = "username", description = FederationDocs.USERNAME),
                    @Parameter(name = "page", description = "Any value asks for a page rather than the collection."),
                    @Parameter(name = "max_id", description = "The cursor from a `next` link.")
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The collection or the page.", content = {
                            @Content(mediaType = ACTIVITY_JSON, schema = @Schema(oneOf = {
                                    OrderedCollection.class, OrderedCollectionPage.class}))
                    }),
                    @ApiResponse(responseCode = "400", description = "`max_id` isn't valid."),
                    @ApiResponse(responseCode = "403", description = "The user is suspended."),
                    @ApiResponse(responseCode = "404", description = "There's no such user."),
                    @ApiResponse(responseCode = "406", description = "The `Accept` header doesn't allow JSON."),
                    @ApiResponse(responseCode = "410", description = "The user was deleted.")
            })
    ResponseEntity<?> following(
            @PathVariable String username,
            @RequestParam(required = false) String page,
            @RequestParam(name = "max_id", required = false) String maxId) {
        return collection(username, page, maxId, false);
    }

    /**
     * Serves the collection, with a link to its first page, or a page of it when {@code page} is given. The pages go from
     * newest to oldest, and each links to the next by the last follow on it. An account that hides its collections only gets
     * its size served, even when a page is asked for.
     *
     * @param followers true for the followers collection, and false for following
     */
    private ResponseEntity<?> collection(String username, String page, String maxId, boolean followers) {
        var actor = actors.findAnyLocalByUsername(username);
        var unavailable = unavailable(actor);
        if (unavailable.isPresent()) {
            return unavailable.get();
        }
        var found = actor.get();
        var uri = followers ? Person.followersOf(found) : Person.followingOf(found);
        var total = followers ? follows.countFollowers(found.id()) : follows.countFollowing(found.id());
        if (actors.hidesCollections(found.id())) {
            return ResponseEntity.ok(OrderedCollection.ofSize(uri, total));
        }
        if (page == null) {
            return ResponseEntity.ok(OrderedCollection.paged(uri, total, pageUri(uri, null)));
        }
        Optional<UUID> cursor = Optional.empty();
        if (maxId != null) {
            cursor = Ids.parse(maxId);
            if (cursor.isEmpty()) {
                return ResponseEntity.badRequest().build();
            }
        }
        var selection = new FollowPage(COLLECTION_PAGE_SIZE, cursor.orElse(null), null, null);
        var entries = followers ? follows.followers(found.id(), selection) : follows.following(found.id(), selection);
        var next = entries.size() == COLLECTION_PAGE_SIZE ? pageUri(uri, entries.getLast().followId()) : null;
        return ResponseEntity.ok(OrderedCollectionPage.of(
                pageUri(uri, cursor.orElse(null)), total, uri, next, entries.stream().map(FollowEntry::actorUri).toList()));
    }

    /**
     * Like {@link #followers}. Statuses that anyone can see are served whoever asks, so the signature isn't read.
     */
    @CrossOrigin
    @GetMapping(path = "/users/{username}/outbox", produces = {ACTIVITY_JSON, LD_JSON, ANY_LD_JSON})
    @Operation(
            operationId = "getOutbox",
            summary = "Fetch a user's outbox",
            description = FederationDocs.OUTBOX,
            parameters = {
                    @Parameter(name = "username", description = FederationDocs.USERNAME),
                    @Parameter(name = "page", description = "Any value asks for a page rather than the collection."),
                    @Parameter(name = "max_id", description = "The cursor from a `next` link.")
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The collection or the page.", content = {
                            @Content(mediaType = ACTIVITY_JSON, schema = @Schema(oneOf = {
                                    OrderedCollection.class, OrderedCollectionPage.class}))
                    }),
                    @ApiResponse(responseCode = "400", description = "`max_id` isn't valid."),
                    @ApiResponse(responseCode = "403", description = "The user is suspended."),
                    @ApiResponse(responseCode = "404", description = "There's no such user."),
                    @ApiResponse(responseCode = "406", description = "The `Accept` header doesn't allow JSON."),
                    @ApiResponse(responseCode = "410", description = "The user was deleted.")
            })
    ResponseEntity<?> outbox(
            @PathVariable String username,
            @RequestParam(required = false) String page,
            @RequestParam(name = "max_id", required = false) String maxId) {
        var actor = actors.findAnyLocalByUsername(username);
        var unavailable = unavailable(actor);
        if (unavailable.isPresent()) {
            return unavailable.get();
        }
        var author = actor.get();
        var uri = author.outboxUri();
        var total = statuses.countVisibleToAnyone(author.id());
        if (page == null) {
            return ResponseEntity.ok(OrderedCollection.paged(uri, total, pageUri(uri, null)));
        }
        var cursor = maxId == null ? Optional.<UUID>empty() : Ids.parse(maxId);
        if (maxId != null && cursor.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        var entries = statuses.findByAccount(author.id(), ANONYMOUS, false, false,
                new StatusPage(COLLECTION_PAGE_SIZE, cursor.orElse(null), null, null));
        // A boost whose status can't be listed is left out, but still moves the cursor, so a page can be short.
        var next = entries.size() == COLLECTION_PAGE_SIZE ? pageUri(uri, entries.getLast().id()) : null;
        return ResponseEntity.ok(OrderedCollectionPage.of(
                pageUri(uri, cursor.orElse(null)), total, uri, next, outboxItems(author, entries)));
    }

    private List<Object> outboxItems(Actor author, List<Status> entries) {
        var noteIds = entries.stream().filter(status -> !status.isReblog()).map(Status::id).toList();
        var mentions = statuses.findMentions(noteIds);
        var tags = statuses.findTags(noteIds);
        var boosted = statuses.findVisibleToAnyone(entries.stream().filter(Status::isReblog).map(Status::reblogOfId).toList())
                .stream().collect(Collectors.toMap(Status::id, Function.identity()));
        var boostedAuthors = activeActors(boosted.values().stream().map(Status::actorId).distinct().toList());
        var items = new ArrayList<Object>();
        for (var status : entries) {
            if (!status.isReblog()) {
                var note = notes.of(status, author, mentions.getOrDefault(status.id(), List.of()),
                        tags.getOrDefault(status.id(), List.of()));
                items.add(Create.of(note).withoutContext());
                continue;
            }
            var original = boosted.get(status.reblogOfId());
            var originalAuthor = original == null ? null : boostedAuthors.get(original.actorId());
            if (originalAuthor != null) {
                items.add(Announce.fromHere(status.uri(), author, original.uri(), originalAuthor, status.visibility(),
                        status.publishedAt()).withoutContext());
            }
        }
        return items;
    }

    /**
     * The instance actor posts nothing, but its document advertises an outbox, as ActivityPub requires of an actor.
     */
    @CrossOrigin
    @GetMapping(path = InstanceActor.PATH + "/outbox", produces = {ACTIVITY_JSON, LD_JSON, ANY_LD_JSON})
    @Operation(
            operationId = "getInstanceActorOutbox",
            summary = "Fetch the server's own actor's outbox",
            description = "An empty `OrderedCollection`. " + FederationDocs.DOCUMENT + " " + FederationDocs.UNSIGNED,
            responses = @ApiResponse(responseCode = "200", description = "The collection.", content = {
                    @Content(mediaType = ACTIVITY_JSON, schema = @Schema(implementation = OrderedCollection.class)),
            }))
    OrderedCollection instanceActorOutbox() {
        return OrderedCollection.ofSize(actors.instanceActor().outboxUri(), 0);
    }

    /**
     * The answer for a user's collection when the user can't be served, as for followers, following, and the outbox.
     *
     * @return empty if the user exists and is active
     */
    private static Optional<ResponseEntity<?>> unavailable(Optional<Actor> actor) {
        if (actor.isEmpty()) {
            return Optional.of(ResponseEntity.notFound().build());
        }
        if (!actor.get().isActive()) {
            return Optional.of(ResponseEntity.status(actor.get().isDeleted() ? HttpStatus.GONE : HttpStatus.FORBIDDEN).build());
        }
        return Optional.empty();
    }

    private static URI pageUri(URI collection, UUID maxId) {
        var builder = UriComponentsBuilder.fromUri(collection).queryParam("page", "true");
        if (maxId != null) {
            builder.queryParam("max_id", maxId);
        }
        return builder.build().toUri();
    }

    @GetMapping(path = "/users/{username}/statuses/{id}/likes", produces = {ACTIVITY_JSON, LD_JSON, ANY_LD_JSON})
    @Operation(operationId = "getStatusLikes", summary = "Fetch a status's likes",
            description = """
                    An OrderedCollection with totalItems and a first page at ?page=true. Pages contain up to 40
                    Like activities, newest first, with max_id cursors. Only existing public and unlisted posts
                    are available; private, direct, and deleted posts get a 404. Suspended and deleted likers
                    and actors on blocked domains are excluded. HTML requests redirect to the post's page.
                    """ + FederationDocs.NEGOTIATED,
            responses = {
                    @ApiResponse(responseCode = "200", description = "The collection or its page."),
                    @ApiResponse(responseCode = "400", description = "The cursor is invalid."),
                    @ApiResponse(responseCode = "403", description = "The author is suspended."),
                    @ApiResponse(responseCode = "404", description = "No publicly fetchable post by this author."),
                    @ApiResponse(responseCode = "406", description = "The Accept header allows neither type.")
            })
    ResponseEntity<?> likes(@PathVariable String username, @PathVariable String id,
                            @RequestParam(required = false) String page,
                            @RequestParam(name = "max_id", required = false) String maxId) {
        return collectionOf(username, id, (status, author) -> {
            var uri = URI.create(status.uri() + "/likes");
            var total = likes.count(status.id());
            if (page == null) return ResponseEntity.ok(OrderedCollection.paged(uri, total, pageUri(uri, null)));
            var cursor = maxId == null ? Optional.<UUID>empty() : Ids.parse(maxId);
            if (maxId != null && cursor.isEmpty()) return ResponseEntity.badRequest().build();
            // One extra item makes next accurate for a full final page.
            var selection = likes.page(status.id(), cursor.orElse(null), COLLECTION_PAGE_SIZE + 1);
            var entries = selection.stream().limit(COLLECTION_PAGE_SIZE).toList();
            var next = selection.size() > COLLECTION_PAGE_SIZE ? pageUri(uri, entries.getLast().id()) : null;
            var people = activeActors(entries.stream().map(StatusLike::actorId).toList());
            var items = entries.stream().filter(entry -> people.containsKey(entry.actorId()))
                    .map(entry -> Like.of(entry.activityUri(), people.get(entry.actorId()).uri(), status.uri(), author.uri()).withoutContext())
                    .toList();
            return ResponseEntity.ok(OrderedCollectionPage.of(pageUri(uri, cursor.orElse(null)), total, uri, next, items));
        });
    }

    @GetMapping(path = "/users/{username}/statuses/{id}/shares", produces = {ACTIVITY_JSON, LD_JSON, ANY_LD_JSON})
    @Operation(operationId = "getStatusShares", summary = "Fetch a status's boosts",
            description = """
                    An OrderedCollection with totalItems and a first page at ?page=true. Pages contain up to 40
                    Announce activities, newest first, with max_id cursors. Only existing public and unlisted posts
                    are available; private, direct, and deleted posts get a 404. Only public and unlisted boosts are
                    listed or counted, each addressed only to the public. Suspended and deleted boosters and actors on
                    blocked domains are excluded. HTML requests redirect to the post's page.
                    """ + FederationDocs.NEGOTIATED,
            responses = {
                    @ApiResponse(responseCode = "200", description = "The collection or its page."),
                    @ApiResponse(responseCode = "400", description = "The cursor is invalid."),
                    @ApiResponse(responseCode = "403", description = "The author is suspended."),
                    @ApiResponse(responseCode = "404", description = "No publicly fetchable post by this author."),
                    @ApiResponse(responseCode = "406", description = "The Accept header allows neither type.")
            })
    ResponseEntity<?> shares(@PathVariable String username, @PathVariable String id,
                             @RequestParam(required = false) String page,
                             @RequestParam(name = "max_id", required = false) String maxId) {
        return collectionOf(username, id, (status, author) -> {
            var uri = URI.create(status.uri() + "/shares");
            var total = boosts.countPublic(status.id());
            if (page == null) return ResponseEntity.ok(OrderedCollection.paged(uri, total, pageUri(uri, null)));
            var cursor = maxId == null ? Optional.<UUID>empty() : Ids.parse(maxId);
            if (maxId != null && cursor.isEmpty()) return ResponseEntity.badRequest().build();
            var selection = boosts.pagePublic(status.id(), cursor.orElse(null), COLLECTION_PAGE_SIZE + 1);
            var entries = selection.stream().limit(COLLECTION_PAGE_SIZE).toList();
            var next = selection.size() > COLLECTION_PAGE_SIZE ? pageUri(uri, entries.getLast().id()) : null;
            var people = activeActors(entries.stream().map(StatusBoost::actorId).toList());
            var items = entries.stream().filter(entry -> people.containsKey(entry.actorId()))
                    .map(entry -> Announce.inCollection(entry.uri().toString(), people.get(entry.actorId()).uri(),
                            status.uri(), entry.visibility(), entry.publishedAt()))
                    .toList();
            return ResponseEntity.ok(OrderedCollectionPage.of(pageUri(uri, cursor.orElse(null)), total, uri, next, items));
        });
    }

    /**
     * Answers for a collection of a local status that anyone can see. Others get a 404, like the status itself.
     */
    private ResponseEntity<?> collectionOf(
            String username, String id, BiFunction<Status, Actor, ResponseEntity<?>> collection) {
        var author = actors.findAnyLocalByUsername(username);
        var statusId = Ids.parse(id);
        if (author.isEmpty() || statusId.isEmpty() || author.get().isDeleted()) return ResponseEntity.notFound().build();
        if (author.get().isSuspended()) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return statuses.findVisible(statusId.get(), ANONYMOUS)
                .filter(status -> !status.isReblog() && status.actorId().equals(author.get().id()))
                .map(status -> collection.apply(status, author.get()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<UUID, Actor> activeActors(List<UUID> ids) {
        return actors.findByIds(ids).stream()
                .filter(Actor::isActive)
                .collect(Collectors.toMap(Actor::id, Function.identity()));
    }

    /**
     * This must never require a signature, even if other documents come to. A server checking this server's signature
     * reads this key, and may need its own request signed in turn, so the two could end up waiting on each other.
     */
    @CrossOrigin
    @GetMapping(path = InstanceActor.PATH, produces = {ACTIVITY_JSON, LD_JSON, ANY_LD_JSON})
    @Operation(
            operationId = "getInstanceActor",
            summary = "Fetch the server's own actor document",
            description = """
                    An `Application` with the `preferredUsername` `""" + InstanceActor.USERNAME + """
                    `, for signing requests that no user is behind, such as fetches of keys and actor documents. Its \
                    key is identified as `{actor-uri}#main-key`, and its `url` is the front page. It can't be followed, \
                    so a `Follow` of it is ignored. It never needs a signature.\s""" + FederationDocs.DOCUMENT,
            responses = @ApiResponse(responseCode = "200", description = "The actor.", content = {
                    @Content(mediaType = ACTIVITY_JSON, schema = @Schema(implementation = Application.class)),
            }))
    Application instanceActor() {
        return Application.from(actors.instanceActor(), instance.sharedInboxUri());
    }

    @GetMapping(path = "/users/{username}/statuses/{id}", produces = {ACTIVITY_JSON, LD_JSON, ANY_LD_JSON})
    @Operation(
            operationId = "getNote",
            summary = "Fetch a status as a Note",
            description = """
                    Public and unlisted statuses are `Note` objects. A signature is read if there is one, but never \
                    required. A private status is only served to a signer who was already following its author when \
                    it was posted, or that it mentions, and a direct one to a signer it mentions. A signature that \
                    can't be accepted is answered as if there were none. A deleted public or unlisted status gets a \
                    410 with a `Tombstone`, and a deleted private or direct one a 404. Answers have \
                    `Vary: Signature`, and a private or direct `Note` has `Cache-Control: no-store, private`. A \
                    browser can't send a `Signature` header from another origin.\s""" + FederationDocs.CONTENT_NEGOTIATION,
            parameters = {
                    @Parameter(name = "username", description = "The author's username, matched case-insensitively."),
                    @Parameter(name = "id", description = "The status's ID.")
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The `Note`, or the status's page for HTML.",
                            content = {
                            @Content(mediaType = ACTIVITY_JSON, schema = @Schema(implementation = Note.class)),
                            @Content(mediaType = MediaType.TEXT_HTML_VALUE, schema = @Schema(type = "string"))
                    }),
                    @ApiResponse(responseCode = "403", description = "The author is suspended."),
                    @ApiResponse(responseCode = "404", description = """
                            There's no such status by this author, or the signer can't see it."""),
                    @ApiResponse(responseCode = "406", description = "The `Accept` header allows neither type."),
                    @ApiResponse(responseCode = "410", description = """
                            The status was deleted, with a `Tombstone` whose `formerType` is `Note`, or the author \
                            was deleted, which gives the same answer for any ID.""", content = {
                            @Content(mediaType = ACTIVITY_JSON, schema = @Schema(implementation = Tombstone.class)),
                    })
            })
    ResponseEntity<?> note(
            @PathVariable String username,
            @PathVariable String id,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.addHeader(HttpHeaders.VARY, FetchSigners.SIGNATURE);
        // Before anything is looked up, so that how long this takes doesn't depend on whether a private status exists.
        var signer = signers.signer(request).map(Actor::id);
        var author = actors.findAnyLocalByUsername(username);
        var statusId = Ids.parse(id);
        if (author.isEmpty() || statusId.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (author.get().isDeleted()) {
            var uri = URI.create(author.get().uri() + "/statuses/" + statusId.get());
            return ResponseEntity.status(HttpStatus.GONE).body(Tombstone.ofStatus(uri, author.get()));
        }
        // Not a 404 or 410, which other servers can take as the status having been deleted, when this can be undone.
        if (author.get().isSuspended()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        var status = statuses.findFetchable(statusId.get(), signer)
                // Otherwise any account's URL could serve this status. A boost has no object of its own to serve.
                .filter(found -> !found.isReblog() && found.actorId().equals(author.get().id()));
        if (status.isPresent()) {
            var ok = ResponseEntity.ok();
            if (!status.get().isVisibleTo(ANONYMOUS)) {
                // Only the signer could see this. Said here rather than left to Spring Security's default, which would
                // stop applying if Notes were ever made cacheable.
                ok.cacheControl(CacheControl.noStore().cachePrivate());
            }
            return ok.body(notes.of(status.get(), author.get()));
        }
        return statuses.findTombstone(statusId.get(), author.get().id())
                .<ResponseEntity<?>>map(deleted -> ResponseEntity.status(HttpStatus.GONE).body(Tombstone.from(deleted)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
