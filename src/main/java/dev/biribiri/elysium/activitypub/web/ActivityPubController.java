package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.vocab.Application;
import dev.biribiri.elysium.activitypub.outbound.Notes;
import dev.biribiri.elysium.activitypub.vocab.OrderedCollection;
import dev.biribiri.elysium.activitypub.vocab.Person;
import dev.biribiri.elysium.activitypub.vocab.Tombstone;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.actor.InstanceActor;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.graph.FollowService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.ACTIVITY_JSON;
import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.ANY_LD_JSON;
import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.LD_JSON;

@RestController
public class ActivityPubController {

    /**
     * Requests aren't signed yet, so there's no way to tell who is asking. Everything is served as to an anonymous viewer.
     */
    private static final Optional<UUID> ANONYMOUS = Optional.empty();

    private final ActorService actors;
    private final StatusService statuses;
    private final FollowService follows;
    private final Notes notes;

    ActivityPubController(ActorService actors, StatusService statuses, FollowService follows, Notes notes) {
        this.actors = actors;
        this.statuses = statuses;
        this.follows = follows;
        this.notes = notes;
    }

    @GetMapping(path = "/users/{username}", produces = {ACTIVITY_JSON, LD_JSON, ANY_LD_JSON})
    ResponseEntity<Person> actor(@PathVariable String username) {
        return ResponseEntity.of(actors.findLocalByUsername(username).map(Person::from));
    }

    /**
     * Only says how many followers there are, because whether people can hide who follows them is still undecided. There's
     * no web page at this URL.
     */
    @CrossOrigin
    @GetMapping(path = "/users/{username}/followers", produces = {ACTIVITY_JSON, LD_JSON, ANY_LD_JSON})
    ResponseEntity<OrderedCollection> followers(@PathVariable String username) {
        return ResponseEntity.of(actors.findLocalByUsername(username).map(actor ->
                OrderedCollection.ofSize(Person.followersOf(actor), follows.countFollowers(actor.id()))));
    }

    /**
     * This must never require a signature, even if other documents come to. A server checking this server's signature
     * reads this key, and may need its own request signed in turn, so the two could end up waiting on each other.
     */
    @CrossOrigin
    @GetMapping(path = InstanceActor.PATH, produces = {ACTIVITY_JSON, LD_JSON, ANY_LD_JSON})
    Application instanceActor() {
        return Application.from(actors.instanceActor());
    }

    @GetMapping(path = "/users/{username}/statuses/{id}", produces = {ACTIVITY_JSON, LD_JSON, ANY_LD_JSON})
    ResponseEntity<?> note(@PathVariable String username, @PathVariable String id) {
        var author = actors.findLocalByUsername(username);
        var statusId = Ids.parse(id);
        if (author.isEmpty() || statusId.isEmpty()) {
            return ResponseEntity.notFound().build();
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
