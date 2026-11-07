package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.outbound.Notes;
import dev.biribiri.elysium.activitypub.vocab.Application;
import dev.biribiri.elysium.activitypub.vocab.Note;
import dev.biribiri.elysium.activitypub.vocab.OrderedCollection;
import dev.biribiri.elysium.activitypub.vocab.OrderedCollectionPage;
import dev.biribiri.elysium.activitypub.vocab.Person;
import dev.biribiri.elysium.activitypub.vocab.Tombstone;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.actor.InstanceActor;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.content.StatusService;
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
import java.util.Optional;
import java.util.UUID;

import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.ACTIVITY_JSON;
import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.ANY_LD_JSON;
import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.LD_JSON;

@RestController
@Tag(name = FederationDocs.ACTIVITYPUB_TAG, description = """
        ActivityPub documents and inboxes, for other servers. `FEDERATION.md` in the repository describes them in full.""",
        externalDocs = @ExternalDocumentation(url = "https://www.w3.org/TR/activitypub/"))
public class ActivityPubController {

    /**
     * Requests aren't signed yet, so there's no way to tell who is asking. Everything is served as to an anonymous viewer.
     */
    private static final Optional<UUID> ANONYMOUS = Optional.empty();

    static final int COLLECTION_PAGE_SIZE = 40;

    private final ActorService actors;
    private final StatusService statuses;
    private final FollowService follows;
    private final Notes notes;
    private final InstanceProperties instance;
    private final ProfileService profiles;

    ActivityPubController(
            ActorService actors,
            StatusService statuses,
            FollowService follows,
            Notes notes,
            InstanceProperties instance,
            ProfileService profiles) {
        this.instance = instance;
        this.profiles = profiles;
        this.actors = actors;
        this.statuses = statuses;
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
        if (actor.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!actor.get().isActive()) {
            return ResponseEntity.status(actor.get().isDeleted() ? HttpStatus.GONE : HttpStatus.FORBIDDEN).build();
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

    private static URI pageUri(URI collection, UUID maxId) {
        var builder = UriComponentsBuilder.fromUri(collection).queryParam("page", "true");
        if (maxId != null) {
            builder.queryParam("max_id", maxId);
        }
        return builder.build().toUri();
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
                    Public and unlisted statuses are `Note` objects, and private and direct ones get a 404, even for a \
                    follower. A deleted public or unlisted status gets a 410 with a `Tombstone`, and a deleted private \
                    or direct one still gets a 404.\s""" + FederationDocs.NEGOTIATED,
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
                            There's no such status by this author, or it isn't public or unlisted."""),
                    @ApiResponse(responseCode = "406", description = "The `Accept` header allows neither type."),
                    @ApiResponse(responseCode = "410", description = """
                            The status was deleted, with a `Tombstone` whose `formerType` is `Note`, or the author \
                            was deleted, which gives the same answer for any ID.""", content = {
                            @Content(mediaType = ACTIVITY_JSON, schema = @Schema(implementation = Tombstone.class)),
                    })
            })
    ResponseEntity<?> note(@PathVariable String username, @PathVariable String id) {
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
        var status = statuses.findVisible(statusId.get(), ANONYMOUS)
                // Otherwise any account's URL could serve this status.
                .filter(found -> found.actorId().equals(author.get().id()));
        if (status.isPresent()) {
            return ResponseEntity.ok(notes.of(status.get(), author.get()));
        }
        return statuses.findTombstone(statusId.get(), author.get().id())
                .<ResponseEntity<?>>map(deleted -> ResponseEntity.status(HttpStatus.GONE).body(Tombstone.from(deleted)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
