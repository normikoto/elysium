package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.vocab.Person;
import dev.biribiri.elysium.actor.ActorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ActivityPubController {

    private static final String ACTIVITY_JSON = "application/activity+json";
    private static final String LD_JSON = "application/ld+json;profile=\"https://www.w3.org/ns/activitystreams\"";

    /**
     * Matches any profile, because a request for one this server doesn't list would otherwise get the web page. Servers
     * send variations such as the {@code http} form of the ActivityStreams profile, or several profiles at once.
     */
    private static final String ANY_LD_JSON = "application/ld+json";

    private final ActorService actors;

    ActivityPubController(ActorService actors) {
        this.actors = actors;
    }

    @GetMapping(path = "/users/{username}", produces = {ACTIVITY_JSON, LD_JSON, ANY_LD_JSON})
    ResponseEntity<Person> actor(@PathVariable String username) {
        return ResponseEntity.of(actors.findLocalByUsername(username).map(Person::from));
    }
}
