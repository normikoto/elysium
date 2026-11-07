package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.vocab.ActivityStreams;
import dev.biribiri.elysium.activitypub.vocab.JsonResourceDescriptor;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceActor;
import dev.biribiri.elysium.actor.InstanceProperties;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "WebFinger", description = "Finding actors by address.",
        externalDocs = @ExternalDocumentation(url = "https://webfinger.net/"))
public class WebFingerController {

    private final ActorService actors;
    private final InstanceProperties instance;

    WebFingerController(ActorService actors, InstanceProperties instance) {
        this.actors = actors;
        this.instance = instance;
    }

    @CrossOrigin
    @GetMapping(path = "/.well-known/webfinger", produces = {"application/jrd+json", MediaType.APPLICATION_JSON_VALUE})
    @Operation(
            operationId = "webFinger",
            summary = "Find an actor by its address or URL",
            description = """
                    Gives a `self` link to the actor document, of type `application/activity+json`, and an \
                    `http://webfinger.net/rel/profile-page` link to the profile page. The instance actor can be found \
                    as `acct:instance.actor@{domain}` or by `/actor`, and its profile page is the front page. A \
                    suspended user is still found. Served as `application/jrd+json` or `application/json`, and any \
                    origin can read it through CORS.""",
            parameters = @Parameter(name = "resource", required = true, description = """
                    `acct:{username}@{domain}`, or an actor's URL. Only this server's domain is found."""),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The actor's links."),
                    @ApiResponse(responseCode = "400", description = """
                            The resource is missing, isn't a URI, or is an `acct:` URI with no username or domain."""),
                    @ApiResponse(responseCode = "404", description = "No actor was found."),
                    @ApiResponse(responseCode = "410", description = "The user was deleted.")
            })
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
