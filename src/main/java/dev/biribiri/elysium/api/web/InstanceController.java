package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.RegistrationProperties;
import dev.biribiri.elysium.content.StatusProperties;
import dev.biribiri.elysium.content.StatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/instance")
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
