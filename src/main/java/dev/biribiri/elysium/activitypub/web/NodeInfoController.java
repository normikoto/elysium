package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.vocab.NodeInfo;
import dev.biribiri.elysium.activitypub.vocab.NodeInfoLinks;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.RegistrationProperties;
import dev.biribiri.elysium.content.StatusService;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
public class NodeInfoController {

    private static final String DOCUMENT_PATH = "/nodeinfo/2.1";
    private static final String SCHEMA = "http://nodeinfo.diaspora.software/ns/schema/2.1";
    private static final URI REPOSITORY = URI.create("https://github.com/normikoto/elysium");

    private final ActorService actors;
    private final StatusService statuses;
    private final InstanceProperties instance;
    private final BuildProperties build;
    private final RegistrationProperties registration;

    NodeInfoController(
            ActorService actors,
            StatusService statuses,
            InstanceProperties instance,
            BuildProperties build,
            RegistrationProperties registration) {
        this.actors = actors;
        this.statuses = statuses;
        this.instance = instance;
        this.build = build;
        this.registration = registration;
    }

    @CrossOrigin
    @GetMapping(path = "/.well-known/nodeinfo", produces = {"application/jrd+json", MediaType.APPLICATION_JSON_VALUE})
    NodeInfoLinks links() {
        return new NodeInfoLinks(List.of(new NodeInfoLinks.Link(SCHEMA, instance.resolve(DOCUMENT_PATH))));
    }

    @CrossOrigin
    @GetMapping(path = DOCUMENT_PATH, produces = "application/json; profile=\"" + SCHEMA + "#\"")
    NodeInfo nodeInfo() {
        return new NodeInfo(
                "2.1",
                new NodeInfo.Software("elysium", build.getVersion(), REPOSITORY),
                List.of("activitypub"),
                new NodeInfo.Services(List.of(), List.of()),
                registration.enabled(),
                new NodeInfo.Usage(new NodeInfo.Users(actors.countLocal()), statuses.countLocal()),
                Map.of());
    }
}
