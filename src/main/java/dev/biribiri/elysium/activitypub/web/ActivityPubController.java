package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.vocab.Person;
import dev.biribiri.elysium.actor.ActorService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ActivityPubController {

    private static final String ACTIVITY_JSON = "application/activity+json";
    private static final String LD_JSON = "application/ld+json;profile=\"https://www.w3.org/ns/activitystreams\"";

    private final ActorService actors;

    ActivityPubController(ActorService actors) {
        this.actors = actors;
    }

    @CrossOrigin
    @GetMapping(path = "/users/{username}", produces = {ACTIVITY_JSON, LD_JSON})
    ResponseEntity<Person> actor(@PathVariable String username) {
        // The same URL serves a web page to browsers, and a cache must not mix the two up.
        return actors.findLocalByUsername(username)
                .map(actor -> ResponseEntity.ok().varyBy(HttpHeaders.ACCEPT).body(Person.from(actor)))
                .orElseGet(() -> ResponseEntity.notFound().varyBy(HttpHeaders.ACCEPT).build());
    }
}
