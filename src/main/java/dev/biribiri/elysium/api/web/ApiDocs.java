package dev.biribiri.elysium.api.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED_VALUE;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE;

/**
 * The API documentation's description of the server, and what the API's endpoints have in common, so that their
 * annotations only describe what's particular to each. The differences from Mastodon in each endpoint's description have to
 * follow {@code docs/api.md}.
 *
 * <ul>
 * <li>Every error has Mastodon's {@code Error} body. An endpoint that takes a token gets a 401 for one that's invalid, or
 * for none at all if it needs one, and a 403 for one without any of the scopes it lists, which {@code ScopeRequirements}
 * has already listed.
 * <li>A body documented as JSON can also be form-encoded or multipart, with the same fields.
 * <li>A list that takes {@code max_id} is paged by the cursors that {@link PageLinks} links to.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class ApiDocs {

    static final String MASTODON_DOCS = "https://docs.joinmastodon.org/methods/";

    static final String ID = "The ID. An ID that isn't a UUID gets a 404, the same as one that doesn't exist.";

    static final String NOT_FOUND = "Not found, or the ID isn't a UUID.";

    static final String FLAG = """
            Read as Mastodon does: missing or empty is false, `0`, `f`, `false`, and `off` are false, and anything else is \
            true.""";

    static final String NEEDS_USER = """
            Needs a user's token. A token from the `client_credentials` grant gets a 422, with "This method requires an \
            authenticated user".""";

    private static final String FORM_OR_JSON = """
            The body can be form-encoded, multipart, or a JSON object. Values in a JSON body can be strings, or booleans \
            for flags.""";

    private static final String PAGE = """
            Pages go from newest to oldest, unless the endpoint says otherwise. The `Link` header has a `next` link to the \
            page after this one, if this page is full, and a `prev` link to newer results, if it isn't empty. A cursor that \
            isn't a UUID gets a 422.""";

    private static final Map<String, String> CURSORS = Map.of(
            "max_id", "Only results older than this ID.",
            "since_id", "Only results newer than this ID.",
            "min_id", "Only results newer than this ID, starting with the oldest of them.");

    private static final String ERROR_SCHEMA = "Error";

    /**
     * The order the documentation lists its sections in, from what a client needs first. Sections not named here come
     * after these. This runs after the customizer that adds the OAuth tag, which is ordered first.
     */
    private static final List<String> TAG_ORDER = List.of(
            "Apps", "OAuth", "Accounts", "Follow requests", "Blocks", "Mutes", "Domain blocks", "Statuses", "Timelines", "Notifications", "Grouped notifications", "Conversations", "Markers", "Search", "Instance", "Admin: accounts",
            "Admin: domain blocks", "ActivityPub", "WebFinger", "NodeInfo");

    @Bean
    OpenApiCustomizer clientApiDocs(BuildProperties build) {
        return openApi -> {
            describeServer(openApi, build);
            addErrorSchema(openApi);
            openApi.getPaths().forEach((path, item) -> {
                if (path.startsWith("/api/")) {
                    item.readOperations().forEach(ApiDocs::describe);
                }
            });
            if (openApi.getTags() != null) {
                openApi.getTags().sort(Comparator.comparingInt(tag -> {
                    var index = TAG_ORDER.indexOf(tag.getName());
                    return index < 0 ? TAG_ORDER.size() : index;
                }));
            }
        };
    }

    private static void describeServer(OpenAPI openApi, BuildProperties build) {
        openApi.info(new Info()
                .title("Elysium")
                .version(build.getVersion())
                .description("""
                        An ActivityPub server with a Mastodon-compatible client API.

                        The client API, under `/api`, follows [Mastodon's API](https://docs.joinmastodon.org/methods/), \
                        and each endpoint links to Mastodon's documentation for it. Each endpoint's description says how \
                        it differs from Mastodon, and endpoints that Mastodon has but that aren't listed here don't \
                        exist. IDs are UUIDs rather than numbers, and are strings, as Mastodon's are. Errors are JSON \
                        objects with the reason in `error`. Applications authenticate with OAuth 2, as described under \
                        OAuth, and each endpoint lists the scopes it accepts.

                        The federation endpoints, under ActivityPub, WebFinger, and NodeInfo, are for other servers. \
                        `FEDERATION.md` in the repository describes them in full."""));
    }

    private static void addErrorSchema(OpenAPI openApi) {
        var schema = new ObjectSchema()
                .addProperty("error", new StringSchema().description("Why the request failed."))
                .description("Mastodon's Error entity.");
        schema.setRequired(List.of("error"));
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        openApi.getComponents().addSchemas(ERROR_SCHEMA, schema);
    }

    private static void describe(Operation operation) {
        var responses = operation.getResponses();
        var security = operation.getSecurity() == null ? List.<SecurityRequirement>of() : operation.getSecurity();
        if (!security.isEmpty()) {
            var anonymous = security.stream().anyMatch(SecurityRequirement::isEmpty);
            responses.putIfAbsent("401", new ApiResponse().description(anonymous
                    ? "The token is invalid, or has been revoked."
                    : "The token is missing or invalid, or has been revoked."));
            if (security.stream().flatMap(requirement -> requirement.values().stream()).anyMatch(s -> !s.isEmpty())) {
                responses.putIfAbsent("403", new ApiResponse().description("The token has none of the scopes listed."));
            }
        }

        var body = operation.getRequestBody();
        var json = body == null || body.getContent() == null ? null : body.getContent().get(APPLICATION_JSON_VALUE);
        if (json != null) {
            body.getContent()
                    .addMediaType(APPLICATION_FORM_URLENCODED_VALUE, json)
                    .addMediaType(MULTIPART_FORM_DATA_VALUE, json);
            append(operation, FORM_OR_JSON);
        }

        var parameters = operation.getParameters() == null ? List.<Parameter>of() : operation.getParameters();
        if (parameters.stream().anyMatch(parameter -> parameter.getName().equals("max_id"))) {
            parameters.stream()
                    .filter(parameter -> parameter.getDescription() == null && CURSORS.containsKey(parameter.getName()))
                    .forEach(parameter -> parameter.setDescription(CURSORS.get(parameter.getName())));
            responses.putIfAbsent("422", new ApiResponse().description("A cursor or `limit` is invalid."));
            var success = responses.get("200");
            if (success != null) {
                success.addHeaderObject("Link", new io.swagger.v3.oas.models.headers.Header()
                        .description("Links to the next and previous pages.").schema(new StringSchema()));
            }
            append(operation, PAGE);
        }

        // Otherwise springdoc gives errors the body of a successful response.
        var error = new Content().addMediaType(APPLICATION_JSON_VALUE,
                new MediaType().schema(new Schema<>().$ref(Components.COMPONENTS_SCHEMAS_REF + ERROR_SCHEMA)));
        responses.forEach((status, response) -> {
            if (status.startsWith("4")) {
                response.setContent(error);
            }
        });
    }

    private static void append(Operation operation, String paragraph) {
        var description = operation.getDescription();
        operation.setDescription(description == null || description.isBlank() ? paragraph : description + " " + paragraph);
    }
}
