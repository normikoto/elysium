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
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.Comparator;
import java.util.List;

/**
 * The API documentation's description of the server and of the API's errors, and text that several endpoints' descriptions
 * share. The differences from Mastodon in each endpoint's description have to follow {@code docs/api.md}.
 */
@Configuration(proxyBeanMethods = false)
class ApiDocs {

    static final String MASTODON_DOCS = "https://docs.joinmastodon.org/methods/";

    static final String ID = "The ID. An ID that isn't a UUID gets a 404, the same as one that doesn't exist.";

    static final String NOT_FOUND = "Not found, or the ID isn't a UUID.";

    static final String PAGE = """
            Pages go from newest to oldest. The `Link` header has a `next` link to the page after this one, if this page is \
            full, and a `prev` link to newer results, if it isn't empty. A cursor that isn't a UUID gets a 422.""";

    static final String MAX_ID = "Only results older than this ID.";
    static final String SINCE_ID = "Only results newer than this ID.";
    static final String MIN_ID = "Only results newer than this ID, starting with the oldest of them.";

    static final String FLAG = """
            Read as Mastodon does: missing or empty is false, `0`, `f`, `false`, and `off` are false, and anything else is \
            true.""";

    static final String NEEDS_USER = """
            Needs a user's token. A token from the `client_credentials` grant gets a 422, with "This method requires an \
            authenticated user".""";

    static final String FORM_OR_JSON = """
            The body can be form-encoded, multipart, or a JSON object. Values in a JSON body can be strings, or booleans \
            for flags.""";

    private static final String ERROR_SCHEMA = "Error";

    /**
     * The order the documentation lists its sections in, from what a client needs first. Sections not named here come
     * after these.
     */
    private static final List<String> TAG_ORDER = List.of(
            "Apps", "OAuth", "Accounts", "Statuses", "Timelines", "Search", "Instance", "Admin: accounts",
            "Admin: domain blocks", "ActivityPub", "WebFinger", "NodeInfo");

    @Bean
    OpenApiCustomizer serverAndErrorDocs(BuildProperties build) {
        return openApi -> {
            describeServer(openApi, build);
            describeErrors(openApi);
        };
    }

    @Bean
    OpenApiCustomizer tagOrder() {
        return new TagOrder();
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

    /**
     * Every error from the API has the same body, so endpoints only describe when each status is sent. An endpoint that
     * takes a token gets a 401 for one that's invalid, or for none at all if it needs one, and a 403 for one without any
     * of the scopes it lists. {@code ScopeRequirements} has already listed those.
     */
    private static void describeErrors(OpenAPI openApi) {
        var schema = new ObjectSchema()
                .addProperty("error", new StringSchema().description("Why the request failed."))
                .description("Mastodon's Error entity.");
        schema.setRequired(List.of("error"));
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        openApi.getComponents().addSchemas(ERROR_SCHEMA, schema);

        openApi.getPaths().forEach((path, item) -> {
            if (!path.startsWith("/api/")) {
                return;
            }
            for (var operation : item.readOperations()) {
                var responses = operation.getResponses();
                if (operation.getSecurity() != null && !operation.getSecurity().isEmpty()) {
                    addIfMissing(responses, "401", allowsAnonymous(operation)
                            ? "The token is invalid, or has been revoked."
                            : "The token is missing or invalid, or has been revoked.");
                    if (hasScopes(operation)) {
                        addIfMissing(responses, "403", "The token has none of the scopes listed.");
                    }
                }
                // Otherwise springdoc gives errors the body of a successful response.
                responses.forEach((status, response) -> {
                    if (status.startsWith("4")) {
                        response.setContent(new Content().addMediaType(
                                org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + ERROR_SCHEMA))));
                    }
                });
            }
        });
    }

    private static boolean allowsAnonymous(Operation operation) {
        return operation.getSecurity().stream().anyMatch(requirement -> requirement.isEmpty());
    }

    private static boolean hasScopes(Operation operation) {
        return operation.getSecurity().stream()
                .flatMap(requirement -> requirement.values().stream())
                .anyMatch(scopes -> !scopes.isEmpty());
    }

    private static void addIfMissing(ApiResponses responses, String status, String description) {
        if (!responses.containsKey(status)) {
            responses.addApiResponse(status, new ApiResponse().description(description));
        }
    }

    /**
     * Runs after the customizers that add tags, which are either unordered or ordered before this. springdoc sorts
     * customizers by {@link Ordered}, and doesn't see an {@code @Order} on a bean method.
     */
    private static final class TagOrder implements OpenApiCustomizer, Ordered {

        @Override
        public void customise(OpenAPI openApi) {
            if (openApi.getTags() != null) {
                openApi.getTags().sort(Comparator.comparingInt(tag -> {
                    var index = TAG_ORDER.indexOf(tag.getName());
                    return index < 0 ? TAG_ORDER.size() : index;
                }));
            }
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
