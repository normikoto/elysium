package dev.biribiri.elysium.activitypub.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.ACTIVITY_JSON;
import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.ANY_LD_JSON;

/**
 * Finishes the API documentation's federation endpoints. Every inbox takes the same request and gives the same answers, so
 * these are described once here rather than on each handler. They have to follow {@link InboxController} and
 * {@code FEDERATION.md}.
 */
@Configuration(proxyBeanMethods = false)
class FederationApiDocs {

    private static final Set<String> TAGS = Set.of(FederationDocs.ACTIVITYPUB_TAG, "WebFinger", "NodeInfo");

    private static final List<String> INBOXES = List.of(
            InboxController.PERSONAL_INBOX_PATH,
            InboxController.SHARED_INBOX_PATH,
            InboxController.INSTANCE_ACTOR_INBOX_PATH);

    @Bean
    OpenApiCustomizer inboxAndErrorDocs() {
        return FederationApiDocs::customize;
    }

    private static void customize(OpenAPI openApi) {
        for (var path : INBOXES) {
            var item = openApi.getPaths().get(path);
            if (item != null && item.getPost() != null) {
                describeInbox(item.getPost());
            }
        }
        openApi.getPaths().values().stream()
                .flatMap(item -> item.readOperations().stream())
                .filter(operation -> operation.getTags() != null && operation.getTags().stream().anyMatch(TAGS::contains))
                .forEach(FederationApiDocs::removeErrorBodies);
    }

    private static void describeInbox(Operation operation) {
        var body = new ObjectSchema()
                .addProperty("id", new StringSchema())
                .addProperty("type", new StringSchema())
                .addProperty("actor", new StringSchema());
        body.setRequired(List.of("id", "type", "actor"));
        operation.requestBody(new RequestBody()
                .required(true)
                .description("An activity, of up to 1 MiB, with a string `id`, `type`, and `actor`.")
                .content(new Content()
                        .addMediaType(ACTIVITY_JSON, new MediaType().schema(body))
                        .addMediaType(ANY_LD_JSON, new MediaType().schema(body))));
        operation.addParametersItem(header("Signature", "A draft-cavage HTTP signature."));
        operation.addParametersItem(header("Digest", "The body's SHA-256, as `SHA-256=` and then Base64."));

        var responses = operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
        // A successful answer has no body, so springdoc's 200 doesn't apply.
        responses.remove("200");
        responses.putIfAbsent("202", response("""
                The activity is queued, repeats one with the same `id`, was forwarded and will be fetched from its \
                origin, or was dropped."""));
        responses.putIfAbsent("400", response("""
                The signature header can't be read, the body isn't a UTF-8 JSON object with a string `id`, `type`, and \
                `actor`, or the `id` isn't a valid URI or is over 2048 bytes."""));
        responses.putIfAbsent("401", response("There's no signature in a supported format, or it isn't valid."));
        responses.putIfAbsent("403", response("""
                The `id` isn't an HTTPS URL on the same host and port as the `actor`, the signer is suspended, or the \
                signature's key is on a blocked domain."""));
        responses.putIfAbsent("413", response("The body is over 1 MiB."));
        responses.putIfAbsent("415", response("The `Content-Type` isn't one of those accepted."));
        responses.putIfAbsent("429", response("""
                This address has caused too many key fetches, or the signer's server has delivered too much.""")
                .addHeaderObject("Retry-After", new Header()
                        .description("Seconds until there's room again.")
                        .schema(new IntegerSchema())));
        responses.putIfAbsent("503", response("The sender's key can't be fetched right now."));
        operation.responses(sorted(responses));
    }

    /**
     * springdoc gives every response the handler's content types, but these endpoints' errors have no body, except for a
     * {@code Tombstone}, which the handlers document themselves.
     */
    private static void removeErrorBodies(Operation operation) {
        operation.getResponses().forEach((status, response) -> {
            var isError = status.startsWith("4") || status.startsWith("5");
            if (isError && response.getContent() != null && !mentionsTombstone(response.getContent())) {
                response.setContent(null);
            }
        });
    }

    private static boolean mentionsTombstone(Content content) {
        return content.values().stream()
                .anyMatch(type -> type.getSchema() != null && type.getSchema().get$ref() != null
                        && type.getSchema().get$ref().endsWith("/Tombstone"));
    }

    private static ApiResponses sorted(ApiResponses responses) {
        var sorted = new ApiResponses();
        new ArrayList<>(responses.keySet()).stream().sorted().forEach(status -> sorted.put(status, responses.get(status)));
        return sorted;
    }

    private static HeaderParameter header(String name, String description) {
        var parameter = new HeaderParameter();
        parameter.name(name).required(true).description(description).schema(new StringSchema());
        return parameter;
    }

    private static ApiResponse response(String description) {
        return new ApiResponse().description(description);
    }
}
