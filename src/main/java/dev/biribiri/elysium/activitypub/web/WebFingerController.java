package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.vocab.ActivityStreams;
import dev.biribiri.elysium.activitypub.vocab.JsonResourceDescriptor;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceActor;
import dev.biribiri.elysium.actor.InstanceProperties;
import org.springframework.http.HttpStatus;
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

        return switch (uri.getScheme().toLowerCase(Locale.ROOT)) {
            case "acct" -> {
                var account = uri.getSchemeSpecificPart();
                var separator = account.lastIndexOf('@');
                if (separator <= 0 || separator == account.length() - 1) {
                    yield ResponseEntity.badRequest().build();
                }
                var domain = account.substring(separator + 1);
                yield domain.equalsIgnoreCase(instance.domain())
                        ? findByUsername(account.substring(0, separator))
                        : ResponseEntity.notFound().build();
            }
            case "http", "https" -> findByUri(uri);
            default -> ResponseEntity.notFound().build();
        };
    }

    /**
     * Mastodon looks up {@code acct:{preferredUsername}@{domain}} before it accepts a new actor, so the instance actor
     * has to be found here for Mastodon to accept its signatures.
     */
    private ResponseEntity<JsonResourceDescriptor> findByUsername(String username) {
        if (username.equalsIgnoreCase(InstanceActor.USERNAME)) {
            return ResponseEntity.ok(descriptor(actors.instanceActor()));
        }
        return answer(actors.findAnyLocalByUsername(username));
    }

    private ResponseEntity<JsonResourceDescriptor> findByUri(URI uri) {
        if (uri.equals(instance.resolve(InstanceActor.PATH))) {
            return ResponseEntity.ok(descriptor(actors.instanceActor()));
        }
        return answer(actors.findAnyLocalByUri(uri));
    }

    /**
     * A suspended actor is still found, because it can come back.
     */
    private static ResponseEntity<JsonResourceDescriptor> answer(Optional<Actor> actor) {
        if (actor.filter(Actor::isDeleted).isPresent()) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }
        return ResponseEntity.of(actor.map(WebFingerController::descriptor));
    }

    private static JsonResourceDescriptor descriptor(Actor actor) {
        return descriptor(actor.username(), actor.domain(), actor.uri(), actor.uri());
    }

    private static JsonResourceDescriptor descriptor(InstanceActor actor) {
        return descriptor(actor.username(), actor.domain(), actor.uri(), actor.url());
    }

    private static JsonResourceDescriptor descriptor(String username, String domain, URI uri, URI profilePage) {
        return new JsonResourceDescriptor(
                "acct:" + username + "@" + domain,
                List.of(uri),
                List.of(
                        new JsonResourceDescriptor.Link("self", ActivityStreams.ACTIVITY_JSON, uri),
                        new JsonResourceDescriptor.Link(
                                "http://webfinger.net/rel/profile-page", MediaType.TEXT_HTML_VALUE, profilePage)));
    }
}
