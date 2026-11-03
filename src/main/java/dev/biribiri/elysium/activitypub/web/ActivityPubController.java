package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.vocab.Note;
import dev.biribiri.elysium.activitypub.vocab.Person;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

@RestController
public class ActivityPubController {

    private static final String ACTIVITY_JSON = "application/activity+json";
    private static final String LD_JSON = "application/ld+json;profile=\"https://www.w3.org/ns/activitystreams\"";

    /**
     * Matches any profile, because a request for one this server doesn't list would otherwise get the web page. Servers
     * send variations such as the {@code http} form of the ActivityStreams profile, or several profiles at once.
     */
    private static final String ANY_LD_JSON = "application/ld+json";

    /**
     * Requests aren't signed yet, so there's no way to tell who is asking. Everything is served as to an anonymous viewer.
     */
    private static final Optional<UUID> ANONYMOUS = Optional.empty();

    private final ActorService actors;
    private final StatusService statuses;

    ActivityPubController(ActorService actors, StatusService statuses) {
        this.actors = actors;
        this.statuses = statuses;
    }

    @GetMapping(path = "/users/{username}", produces = {ACTIVITY_JSON, LD_JSON, ANY_LD_JSON})
    ResponseEntity<Person> actor(@PathVariable String username) {
        return ResponseEntity.of(actors.findLocalByUsername(username).map(Person::from));
    }

    @GetMapping(path = "/users/{username}/statuses/{id}", produces = {ACTIVITY_JSON, LD_JSON, ANY_LD_JSON})
    ResponseEntity<Note> note(@PathVariable String username, @PathVariable String id) {
        return ResponseEntity.of(actors.findLocalByUsername(username).flatMap(author -> Status.parseId(id)
                .flatMap(statusId -> statuses.findVisible(statusId, ANONYMOUS))
                // Otherwise any account's URL could serve this status.
                .filter(status -> status.actorId().equals(author.id()))
                .map(status -> Note.from(status, author, visibleParent(status)))));
    }

    private URI visibleParent(Status status) {
        return Optional.ofNullable(status.inReplyToId())
                .flatMap(parentId -> statuses.findVisible(parentId, ANONYMOUS))
                .map(Status::uri)
                .orElse(null);
    }
}
