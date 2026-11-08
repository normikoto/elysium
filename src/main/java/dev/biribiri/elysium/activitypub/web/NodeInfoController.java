package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.vocab.JsonResourceDescriptor;
import dev.biribiri.elysium.activitypub.vocab.NodeInfo;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.RegistrationProperties;
import dev.biribiri.elysium.content.StatusService;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

@CrossOrigin
@RestController
@Tag(name = "NodeInfo", description = """
        Information about the server's software and usage. Any origin can read these through CORS.""",
        externalDocs = @ExternalDocumentation(url = "https://nodeinfo.diaspora.software/"))
public class NodeInfoController {

    private static final String PATH_2_0 = "/nodeinfo/2.0";
    private static final String PATH_2_1 = "/nodeinfo/2.1";
    private static final String SCHEMA_2_0 = "http://nodeinfo.diaspora.software/ns/schema/2.0";
    private static final String SCHEMA_2_1 = "http://nodeinfo.diaspora.software/ns/schema/2.1";
    private static final String USAGE = """
            Reports `activitypub` as the protocol, even though federation isn't complete yet. Usage has the number of \
            local users, and `localPosts`, which counts replies but not direct statuses. Active user counts and \
            `localComments` aren't reported.""";

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

    @GetMapping(path = "/.well-known/nodeinfo", produces = {"application/jrd+json", MediaType.APPLICATION_JSON_VALUE})
    @Operation(
            operationId = "nodeInfoLinks",
            summary = "Find the NodeInfo documents",
            description = "Links to the 2.1 and 2.0 documents.",
            responses = @ApiResponse(responseCode = "200", description = "The links."))
    JsonResourceDescriptor links() {
        return JsonResourceDescriptor.ofLinks(List.of(
                new JsonResourceDescriptor.Link(SCHEMA_2_1, instance.resolve(PATH_2_1)),
                new JsonResourceDescriptor.Link(SCHEMA_2_0, instance.resolve(PATH_2_0))));
    }

    @GetMapping(path = PATH_2_1, produces = "application/json; profile=\"" + SCHEMA_2_1 + "#\"")
    @Operation(
            operationId = "nodeInfo21",
            summary = "Get NodeInfo 2.1",
            description = USAGE + " The same as 2.0, plus the software's repository.",
            responses = @ApiResponse(responseCode = "200", description = "The document."))
    NodeInfo nodeInfo2_1() {
        return nodeInfo("2.1", REPOSITORY);
    }

    /**
     * The 2.0 schema doesn't allow a repository.
     */
    @GetMapping(path = PATH_2_0, produces = "application/json; profile=\"" + SCHEMA_2_0 + "#\"")
    @Operation(
            operationId = "nodeInfo20",
            summary = "Get NodeInfo 2.0",
            description = USAGE,
            responses = @ApiResponse(responseCode = "200", description = "The document."))
    NodeInfo nodeInfo2_0() {
        return nodeInfo("2.0", null);
    }

    private NodeInfo nodeInfo(String version, URI repository) {
        return new NodeInfo(
                version,
                new NodeInfo.Software("elysium", build.getVersion(), repository),
                List.of("activitypub"),
                new NodeInfo.Services(List.of(), List.of()),
                registration.acceptsUninvited(),
                new NodeInfo.Usage(new NodeInfo.Users(actors.countLocal()), statuses.countLocal()),
                Map.of());
    }
}
