package dev.biribiri.elysium.api.web;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Describes the streaming WebSocket, which springdoc can't find because it isn't a controller. It's written by hand, so it
 * has to follow {@code api.streaming} and the differences in {@code docs/api.md}.
 */
@Configuration(proxyBeanMethods = false)
class StreamingApiDocs {

    private static final String PATH = "/api/v1/streaming";

    /** {@code OAuthApiDocs}'s name for the security scheme. */
    private static final String SCHEME = "oauth2";

    @Bean
    OpenApiCustomizer streamingWebSocketDocs() {
        return new OrderedCustomizer();
    }

    /**
     * Runs before {@link ApiDocs}, which gives the errors listed here their body.
     */
    private record OrderedCustomizer() implements OpenApiCustomizer, Ordered {

        @Override
        public void customise(OpenAPI openApi) {
            if (openApi.getPaths() == null) {
                openApi.setPaths(new Paths());
            }
            openApi.getPaths().addPathItem(PATH, new PathItem().get(webSocket()));
        }

        @Override
        public int getOrder() {
            return 0;
        }
    }

    private static Operation webSocket() {
        return new Operation()
                .operationId("streamWebSocket")
                .addTagsItem("Streaming")
                .summary("Stream over a WebSocket")
                .description(StreamingController.DIFFERENCES + """
                        A connection can have up to 50 subscriptions.

                        Upgrade to a WebSocket here. Subscribe with `{"type": "subscribe", "stream": "…"}`, adding \
                        `"tag"` for a hashtag stream or `"list"` for a list's, and unsubscribe with \
                        `"type": "unsubscribe"`. The streams are `user`, `user:notification`, `direct`, `list`, \
                        `public`, `public:local`, `public:remote`, each of the last three with `:media`, `hashtag`, \
                        and `hashtag:local`. `user`, `user:notification`, `direct`, and `list` need a user token with \
                        the scopes their REST endpoints need, except that `list` needs `read:statuses`, as on \
                        Mastodon, and the others work without a token. A list that isn't the user's is refused with \
                        401, as on Mastodon.

                        Each event is a message such as `{"stream": ["user"], "event": "update", "payload": "…"}`, \
                        whose payload is the entity as a JSON string, or the status ID for `delete`. A message that \
                        can't be followed is answered with `{"error": "…", "status": 400}`, using 401 or 403 for a \
                        stream that needs a token or scope that the connection doesn't have. The server pings every \
                        30 seconds.

                        The token can also be given as the `access_token` query parameter, or as the \
                        `Sec-WebSocket-Protocol`, which the server then names back, since a browser's WebSocket \
                        can't set headers.""")
                .externalDocs(new ExternalDocumentation().url(ApiDocs.MASTODON_DOCS + "streaming/#websocket"))
                .addParametersItem(new Parameter().in("query").name("stream").schema(new StringSchema())
                        .description("A stream to subscribe to straight away. If it can't be, the error message is "
                                + "sent and the connection closed."))
                .addParametersItem(new Parameter().in("query").name("tag").schema(new StringSchema())
                        .description("The tag for `stream`, if it's a hashtag stream."))
                .addParametersItem(new Parameter().in("query").name("list").schema(new StringSchema())
                        .description("The list's ID for `stream`, if it's a list stream."))
                .addParametersItem(new Parameter().in("query").name("access_token").schema(new StringSchema())
                        .description("The token, for a client that can't send the `Authorization` header."))
                .addSecurityItem(new SecurityRequirement())
                .addSecurityItem(new SecurityRequirement().addList(SCHEME))
                .responses(new ApiResponses()
                        .addApiResponse("101", new ApiResponse().description("Switched to a WebSocket."))
                        .addApiResponse("400", new ApiResponse().description("The request isn't a WebSocket "
                                + "upgrade."))
                        .addApiResponse("401", new ApiResponse().description("The token is invalid.")));
    }
}
