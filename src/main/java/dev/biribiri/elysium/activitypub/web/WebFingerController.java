package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.vocab.JsonResourceDescriptor;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@RestController
public class WebFingerController {

    private final ActorService actors;
    private final InstanceProperties instance;

    WebFingerController(ActorService actors, InstanceProperties instance) {
        this.actors = actors;
        this.instance = instance;
    }

    @CrossOrigin
    @GetMapping(path = "/.well-known/webfinger", produces = {"application/jrd+json", MediaType.APPLICATION_JSON_VALUE})
    ResponseEntity<JsonResourceDescriptor> webFinger(@RequestParam String resource) {
        URI uri;
        try {
            uri = new URI(resource);
        } catch (URISyntaxException e) {
            return ResponseEntity.badRequest().build();
        }
        if (uri.getScheme() == null) {
            return ResponseEntity.badRequest().build();
        }

        Optional<Actor> actor;
        switch (uri.getScheme().toLowerCase(Locale.ROOT)) {
            case "acct" -> {
                var account = uri.getSchemeSpecificPart();
                var separator = account.lastIndexOf('@');
                if (separator <= 0 || separator == account.length() - 1) {
                    return ResponseEntity.badRequest().build();
                }
                var domain = account.substring(separator + 1);
                actor = domain.equalsIgnoreCase(instance.domain())
                        ? actors.findLocalByUsername(account.substring(0, separator))
                        : Optional.empty();
            }
            case "http", "https" -> actor = actors.findLocalByUri(uri);
            default -> actor = Optional.empty();
        }
        return ResponseEntity.of(actor.map(WebFingerController::descriptor));
    }

    private static JsonResourceDescriptor descriptor(Actor actor) {
        return new JsonResourceDescriptor(
                "acct:" + actor.username() + "@" + actor.domain(),
                List.of(actor.uri()),
                List.of(
                        new JsonResourceDescriptor.Link("self", "application/activity+json", actor.uri()),
                        new JsonResourceDescriptor.Link(
                                "http://webfinger.net/rel/profile-page", MediaType.TEXT_HTML_VALUE, actor.uri())));
    }
}
