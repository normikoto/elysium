package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.inbound.Inbox;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.ACTIVITY_JSON;
import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.ANY_LD_JSON;
import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.LD_JSON;

/**
 * Finishes the API documentation of this package's endpoints, which are for other servers:
 *
 * <ul>
 * <li>Only responses whose annotation declares a body have one. springdoc otherwise gives every response the handler's
 * content types, but these endpoints' errors have no body.
 * <li>A body documented as {@code application/activity+json} is also documented as {@code application/ld+json} with the
 * ActivityStreams profile, which is served the same.
 * <li>Every inbox takes the same request, which is described here. {@link InboxController} declares their responses.
 * </ul>
 */
@Component
class FederationApiDocs implements OperationCustomizer {

    @Override
    public io.swagger.v3.oas.models.Operation customize(
            io.swagger.v3.oas.models.Operation operation, HandlerMethod handlerMethod) {
        if (!handlerMethod.getBeanType().getPackage().equals(FederationApiDocs.class.getPackage())) {
            return operation;
        }
        if (handlerMethod.getBeanType() == InboxController.class) {
            describeInbox(operation);
        }
        var withBodies = declaredWithBodies(handlerMethod);
        operation.getResponses().forEach((status, response) -> {
            var isError = status.startsWith("4") || status.startsWith("5");
            if (isError && !withBodies.contains(status)) {
                response.setContent(null);
            }
            var content = response.getContent();
            if (content != null && content.containsKey(ACTIVITY_JSON)) {
                content.putIfAbsent(LD_JSON, content.get(ACTIVITY_JSON));
            }
        });
        var sorted = new ApiResponses();
        sorted.putAll(new TreeMap<>(operation.getResponses()));
        return operation.responses(sorted);
    }

    /**
     * @return the status codes whose response annotation on the handler or its class declares a body
     */
    private static Set<String> declaredWithBodies(HandlerMethod handlerMethod) {
        var method = handlerMethod.getMethod();
        var inOperation = Stream.ofNullable(AnnotatedElementUtils.findMergedAnnotation(method, Operation.class))
                .flatMap(annotation -> Arrays.stream(annotation.responses()));
        var separate = Stream.concat(
                AnnotatedElementUtils.findMergedRepeatableAnnotations(method, ApiResponse.class).stream(),
                AnnotatedElementUtils.findMergedRepeatableAnnotations(handlerMethod.getBeanType(), ApiResponse.class)
                        .stream());
        return Stream.concat(inOperation, separate)
                .filter(response -> response.content().length > 0)
                .map(ApiResponse::responseCode)
                .collect(Collectors.toSet());
    }

    private static void describeInbox(io.swagger.v3.oas.models.Operation operation) {
        var body = new ObjectSchema()
                .addProperty("id", new StringSchema())
                .addProperty("type", new StringSchema())
                .addProperty("actor", new StringSchema());
        body.setRequired(List.of("id", "type", "actor"));
        operation.requestBody(new RequestBody()
                .required(true)
                .description("An activity, of up to " + Inbox.MAX_BODY_BYTES / (1024 * 1024)
                        + " MiB, with a string `id`, `type`, and `actor`.")
                .content(new Content()
                        .addMediaType(ACTIVITY_JSON, new MediaType().schema(body))
                        .addMediaType(ANY_LD_JSON, new MediaType().schema(body))));
        operation.addParametersItem(header("Signature", "A draft-cavage HTTP signature."));
        operation.addParametersItem(header("Digest", "The body's SHA-256, as `SHA-256=` and then Base64."));
    }

    private static Parameter header(String name, String description) {
        return new HeaderParameter().name(name).required(true).description(description).schema(new StringSchema());
    }
}
