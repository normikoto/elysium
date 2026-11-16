package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.inbound.Inbox;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
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
 * <li>A {@link SignedFetch} document gets the answers for a signature that's refused, which {@link SignedFetchInterceptor}
 * gives the same way for each.
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
        var signedFetch = handlerMethod.getMethodAnnotation(SignedFetch.class);
        if (signedFetch != null) {
            describeSignedFetch(operation, signedFetch);
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
        operation.addParametersItem(header("Signature", true,
                "A draft-cavage HTTP signature, or an RFC 9421 one with `Signature-Input`."));
        operation.addParametersItem(header("Signature-Input", false, "Required for an RFC 9421 signature."));
        operation.addParametersItem(header("Digest", false,
                "Required for a draft-cavage signature: the body's SHA-256, as `SHA-256=` and then Base64."));
        operation.addParametersItem(header("Content-Digest", false,
                "Required for an RFC 9421 signature: the body's `sha-256` or `sha-512`, as RFC 9530 describes."));
    }

    private static void describeSignedFetch(io.swagger.v3.oas.models.Operation operation, SignedFetch signedFetch) {
        var responses = operation.getResponses();
        if (!signedFetch.allowUnsigned()) {
            addOrExtend(responses, "401", "Signed fetches are required, and there's no signature in a supported format, "
                    + "or it isn't valid.");
        } else {
            addOrExtend(responses, "401", "Signed fetches are required, and the signature isn't valid.");
        }
        addOrExtend(responses, "403", "Signed fetches are required, and the signer is suspended or deleted, or the "
                + "signature's key is on a blocked domain.");
        addOrExtend(responses, "429", "Signed fetches are required, and this address has caused too many key fetches.");
        responses.get("429").addHeaderObject("Retry-After", new io.swagger.v3.oas.models.headers.Header()
                .description("Seconds until there's room again.")
                .schema(new IntegerSchema()));
        addOrExtend(responses, "503", "Signed fetches are required, and the signer's key can't be fetched right now.");
    }

    /**
     * Adds a response, or adds to the description of one the handler already declares for another reason.
     */
    private static void addOrExtend(ApiResponses responses, String status, String description) {
        var existing = responses.get(status);
        if (existing == null) {
            responses.addApiResponse(status, new io.swagger.v3.oas.models.responses.ApiResponse().description(description));
        } else {
            var before = existing.getDescription().strip();
            if (before.endsWith(".")) {
                before = before.substring(0, before.length() - 1);
            }
            existing.setDescription(before + ", or " + Character.toLowerCase(description.charAt(0)) + description.substring(1));
        }
    }

    private static Parameter header(String name, boolean required, String description) {
        return new HeaderParameter().name(name).required(required).description(description).schema(new StringSchema());
    }
}
