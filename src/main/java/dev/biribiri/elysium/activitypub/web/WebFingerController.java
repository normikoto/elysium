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

        if (isDeleted(uri)) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        Optional<JsonResourceDescriptor> descriptor;
        switch (uri.getScheme().toLowerCase(Locale.ROOT)) {
            case "acct" -> {
                var account = uri.getSchemeSpecificPart();
                var separator = account.lastIndexOf('@');
                if (separator <= 0 || separator == account.length() - 1) {
                    return ResponseEntity.badRequest().build();
                }
                var domain = account.substring(separator + 1);
                descriptor = domain.equalsIgnoreCase(instance.domain())
                        ? findByUsername(account.substring(0, separator))
                        : Optional.empty();
            }
            case "http", "https" -> descriptor = findByUri(uri);
            default -> descriptor = Optional.empty();
        }
        return ResponseEntity.of(descriptor);
    }

    private boolean isDeleted(URI resource) {
        var scheme = resource.getScheme().toLowerCase(Locale.ROOT);
        Optional<Actor> actor = Optional.empty();
        if (scheme.equals("acct")) {
            var account = resource.getSchemeSpecificPart();
            var separator = account.lastIndexOf('@');
            if (separator > 0 && account.substring(separator + 1).equalsIgnoreCase(instance.domain())) {
                actor = actors.findAnyLocalByUsername(account.substring(0, separator));
            }
        } else if (scheme.equals("http") || scheme.equals("https")) {
            actor = actors.findAnyLocalByUri(resource);
        }
        return actor.filter(Actor::isDeleted).isPresent();
    }

    /**
     * Mastodon looks up {@code acct:{preferredUsername}@{domain}} before it accepts a new actor, so the instance actor
     * has to be found here for Mastodon to accept its signatures.
     */
    private Optional<JsonResourceDescriptor> findByUsername(String username) {
        if (username.equalsIgnoreCase(InstanceActor.USERNAME)) {
            return Optional.of(descriptor(actors.instanceActor()));
        }
        return actors.findAnyLocalByUsername(username).map(WebFingerController::descriptor);
    }

    private Optional<JsonResourceDescriptor> findByUri(URI uri) {
        if (uri.equals(instance.resolve(InstanceActor.PATH))) {
            return Optional.of(descriptor(actors.instanceActor()));
        }
        return actors.findAnyLocalByUri(uri).map(WebFingerController::descriptor);
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
