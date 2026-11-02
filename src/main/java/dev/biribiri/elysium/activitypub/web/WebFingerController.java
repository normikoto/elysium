package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.vocab.JsonResourceDescriptor;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class WebFingerController {

    private static final String ACCT_PREFIX = "acct:";

    private final ActorService actors;
    private final InstanceProperties instance;

    WebFingerController(ActorService actors, InstanceProperties instance) {
        this.actors = actors;
        this.instance = instance;
    }

    @GetMapping(path = "/.well-known/webfinger", produces = {"application/jrd+json", MediaType.APPLICATION_JSON_VALUE})
    ResponseEntity<JsonResourceDescriptor> webFinger(@RequestParam String resource) {
        if (!resource.startsWith(ACCT_PREFIX)) {
            return ResponseEntity.badRequest().build();
        }
        var account = resource.substring(ACCT_PREFIX.length());
        var separator = account.lastIndexOf('@');
        if (separator <= 0 || separator == account.length() - 1) {
            return ResponseEntity.badRequest().build();
        }

        var username = account.substring(0, separator);
        var domain = account.substring(separator + 1);
        if (!domain.equalsIgnoreCase(instance.domain())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.of(actors.findLocalByUsername(username).map(WebFingerController::descriptor));
    }

    private static JsonResourceDescriptor descriptor(Actor actor) {
        return new JsonResourceDescriptor(
                ACCT_PREFIX + actor.username() + "@" + actor.domain(),
                List.of(actor.uri()),
                List.of(new JsonResourceDescriptor.Link("self", "application/activity+json", actor.uri())));
    }
}
