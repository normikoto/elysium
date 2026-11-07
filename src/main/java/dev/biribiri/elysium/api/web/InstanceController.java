package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.RegistrationProperties;
import dev.biribiri.elysium.content.StatusProperties;
import dev.biribiri.elysium.content.StatusService;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/instance")
@Tag(name = "Instance", description = "Information about the server.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "instance/"))
public class InstanceController {

    /**
     * Clients parse this to detect features, so it has to start with a Mastodon version number.
     */
    private static final String VERSION = "4.0.0 (compatible; Elysium)";

    private final InstanceProperties instance;
    private final StatusProperties statusProperties;
    private final ActorService actors;
    private final StatusService statuses;
    private final RegistrationProperties registration;

    InstanceController(
            InstanceProperties instance,
            StatusProperties statusProperties,
            ActorService actors,
            StatusService statuses,
            RegistrationProperties registration) {
        this.instance = instance;
        this.statusProperties = statusProperties;
        this.actors = actors;
        this.statuses = statuses;
        this.registration = registration;
    }

    @GetMapping
    @Operation(
            operationId = "getInstanceV1",
            summary = "View information about the server",
            description = """
                    Differences from Mastodon: the status length limit is in `max_toot_chars` and \
                    `configuration.statuses.max_characters`, and defaults to\s""" + StatusProperties.DEFAULT_MAX_CHARACTERS
                    + """
                     rather than 500. `/api/v2/instance` isn't implemented, so clients that don't read the limit \
                    from this may apply their own limit of 500. `version` is a Mastodon version followed by `(compatible; Elysium)`, for \
                    clients that check it to decide what to use. The streaming API isn't implemented, though \
                    `urls.streaming_api` is set.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "instance/#v1"),
            responses = @ApiResponse(responseCode = "200", description = "Information about the server."))
    public InstanceResponse instance() {
        var maxCharacters = statusProperties.maxCharacters();
        var domain = instance.domain();
        return new InstanceResponse(
                domain,
                domain,
                "",
                "",
                "",
                VERSION,
                new InstanceResponse.Urls(instance.baseUrl().toString().replaceFirst("^http", "ws")),
                new InstanceResponse.Stats(actors.countLocal(), statuses.countLocal(), 0),
                null,
                List.of(),
                registration.enabled(),
                false,
                false,
                maxCharacters,
                new InstanceResponse.Configuration(
                        new InstanceResponse.Accounts(0),
                        new InstanceResponse.Statuses(maxCharacters, 0, statuses.charactersReservedPerUrl()),
                        new InstanceResponse.MediaAttachments(List.of(), 0, 0, 0, 0, 0),
                        new InstanceResponse.Polls(0, 0, 0, 0)),
                null,
                List.of());
    }
}
