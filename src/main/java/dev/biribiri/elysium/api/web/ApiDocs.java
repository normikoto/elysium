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
 * <li>A body documented as JSON can also be form-encoded or multipart, with the same fields. A multipart one gets a 411
 * without a length, and a 413 over its endpoint's limit, from {@link UploadLengthFilter}.
 * <li>A list that takes {@code max_id} is paged by the cursors that {@link PageLinks} links to.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class ApiDocs {

    static final String MASTODON_DOCS = "https://docs.joinmastodon.org/methods/";

    static final String ID = "The ID must be a UUID. Unknown or malformed IDs get 404.";

    static final String NOT_FOUND = "The resource was not found, or the ID is malformed.";

    static final String FLAG = """
            As on Mastodon, missing values, empty values, `0`, `f`, `false`, and `off` mean false. Other values mean true.""";

    static final String NEEDS_USER = """
            Use a user token. A `client_credentials` token gets 422 with "This method requires an authenticated user".""";

    private static final String FORM_OR_JSON = """
            Send a form-encoded body, multipart body, or JSON object. JSON accepts strings and booleans for flags.""";

    private static final String PAGE = """
            Results are newest first unless the endpoint specifies another order. On full pages, the `Link` header \
            includes `next` for the following page. On nonempty pages, it includes `prev` for newer results. A cursor \
            that is not a UUID gets 422.""";

    private static final Map<String, String> CURSORS = Map.of(
            "max_id", "Return results older than this ID.",
            "since_id", "Return results newer than this ID.",
            "min_id", "Return results newer than this ID, oldest first.");

    private static final String ERROR_SCHEMA = "Error";

    /**
     * The order the documentation lists its sections in, from what a client needs first. Sections not named here come
     * after these. This runs after the customizer that adds the OAuth tag, which is ordered first.
     */
    private static final List<String> TAG_ORDER = List.of(
            "Apps", "OAuth", "Accounts", "Follow requests", "Blocks", "Mutes", "Domain blocks", "Endorsements",
            "Featured tags", "Statuses", "Bookmarks", "Timelines",
            "Filters", "Filters (v1)", "Notifications", "Grouped notifications", "Conversations", "Markers", "Streaming",
            "Search", "Instance", "Announcements", "Admin: accounts", "Admin: domain blocks", "ActivityPub", "WebFinger", "NodeInfo");

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

                        The client API under `/api` follows [Mastodon's API](https://docs.joinmastodon.org/methods/). \
                        Each endpoint links to Mastodon's documentation and lists Elysium's differences. \
                        Unlisted endpoints are unavailable. IDs are UUID strings.

                        JSON errors give the reason in `error`. Applications authenticate with OAuth 2. Each \
                        endpoint lists the scopes it accepts.

                        Other servers use the ActivityPub, WebFinger, and NodeInfo endpoints. See `FEDERATION.md` \
                        for the full federation reference."""));
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
                responses.putIfAbsent("403", new ApiResponse().description("The token has none of the accepted scopes."));
            }
        }

        var body = operation.getRequestBody();
        var content = body == null ? null : body.getContent();
        var json = content == null ? null : content.get(APPLICATION_JSON_VALUE);
        if (json != null) {
            content
                    .addMediaType(APPLICATION_FORM_URLENCODED_VALUE, json)
                    .addMediaType(MULTIPART_FORM_DATA_VALUE, json);
            append(operation, FORM_OR_JSON);
        }
        if (content != null && content.containsKey(MULTIPART_FORM_DATA_VALUE)) {
            responses.putIfAbsent("411", new ApiResponse().description(
                    "A multipart body has no `Content-Length`, such as one sent chunked."));
            responses.putIfAbsent("413", new ApiResponse().description("The body is too large."));
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
        operation.setDescription(description == null || description.isBlank()
                ? paragraph : description.stripTrailing() + "\n\n" + paragraph);
    }
}
