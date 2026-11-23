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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@RestController
@Tag(name = "WebFinger", description = "Finding actors by address.",
        externalDocs = @ExternalDocumentation(url = "https://webfinger.net/"))
public class WebFingerController {

    /**
     * Where a web page in the {@code web} module lets a logged-in user follow an account they name.
     */
    private static final String AUTHORIZE_INTERACTION_PATH = "/authorize_interaction";

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
                    Gives a `self` link to the actor document, of type `""" + ActivityStreams.ACTIVITY_JSON + """
                    `, and an `http://webfinger.net/rel/profile-page` link to the profile page. A user's response also includes \
                    `http://ostatus.org/schema/1.0/subscribe` and `https://w3id.org/fep/3b86/Follow` templates, which \
                    lead to `{base-url}/authorize_interaction?uri=…`, where they can follow the account given as `uri`. \
                    The instance actor can be found as `acct:""" + InstanceActor.USERNAME + "@{domain}` or by its URL, `{base-url}"
                    + InstanceActor.PATH + """
                    `, and its profile page is the front page. Suspended users are still discoverable.

                    Serves `application/jrd+json` or `application/json` with CORS from any origin.""",
            parameters = @Parameter(name = "resource", required = true, description = """
                    `acct:{username}@{domain}`, or an actor's URL. `{domain}` is the account domain, or the base URL's \
                    host, which other servers look up first when the two differ. The answer's `subject` always has the \
                    account domain. No other domain is found."""),
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
                yield instance.isLocalDomain(domain)
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
    private ResponseEntity<JsonResourceDescriptor> answer(Optional<Actor> actor) {
        if (actor.filter(Actor::isDeleted).isPresent()) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }
        return ResponseEntity.of(actor.map(this::descriptor));
    }

    private JsonResourceDescriptor descriptor(Actor actor) {
        var links = new ArrayList<>(links(actor.uri(), actor.uri()));
        // What other servers' remote follow sends a user here to, so that they can follow from a profile there.
        var authorize = instance.resolve(AUTHORIZE_INTERACTION_PATH) + "?uri=";
        links.add(JsonResourceDescriptor.Link.template(JsonResourceDescriptor.OSTATUS_SUBSCRIBE, authorize + "{uri}"));
        links.add(JsonResourceDescriptor.Link.template(JsonResourceDescriptor.FOLLOW_INTENT, authorize + "{object}"));
        return descriptor(actor.username(), actor.domain(), actor.uri(), links);
    }

    /**
     * The instance actor can't be followed, so it has no templates.
     */
    private static JsonResourceDescriptor descriptor(InstanceActor actor) {
        return descriptor(actor.username(), actor.domain(), actor.uri(), links(actor.uri(), actor.url()));
    }

    private static List<JsonResourceDescriptor.Link> links(URI uri, URI profilePage) {
        return List.of(
                new JsonResourceDescriptor.Link("self", ActivityStreams.ACTIVITY_JSON, uri),
                new JsonResourceDescriptor.Link(
                        "http://webfinger.net/rel/profile-page", MediaType.TEXT_HTML_VALUE, profilePage));
    }

    private static JsonResourceDescriptor descriptor(
            String username, String domain, URI uri, List<JsonResourceDescriptor.Link> links) {
        return new JsonResourceDescriptor("acct:" + username + "@" + domain, List.of(uri), links);
    }
}
