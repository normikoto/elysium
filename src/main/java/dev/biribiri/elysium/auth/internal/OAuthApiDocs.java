package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.InstanceProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Describes the OAuth endpoints, which springdoc can't find because Spring Authorization Server handles them in filters
 * rather than controllers, and the security scheme that the API's endpoints refer to. These are written by hand, so they
 * have to follow {@link SecurityConfig} and {@link OAuthResponses}, and the differences from Mastodon in {@code docs/api.md}.
 * {@link ScopeRequirements} says which scopes each API endpoint needs.
 */
@Configuration(proxyBeanMethods = false)
class OAuthApiDocs {

    /**
     * The name that security requirements use to refer to the scheme.
     */
    static final String SCHEME = "oauth2";

    private static final String TAG = "OAuth";
    private static final String FORM = org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED_VALUE;
    private static final String JSON = org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
    private static final String HTML = org.springframework.http.MediaType.TEXT_HTML_VALUE;

    private static final String TOKEN_SCHEMA = "Token";
    private static final String ERROR_SCHEMA = "OAuthError";
    private static final String METADATA_SCHEMA = "AuthorizationServerMetadata";

    private static final String CLIENT_AUTHENTICATION = """
            The client authenticates with `client_id` and `client_secret` in the body, or with HTTP Basic. \
            Only form-encoded bodies are accepted, not JSON.""";

    @Bean
    OpenApiCustomizer oauthApiDocs(InstanceProperties instance) {
        return openApi -> customize(openApi, instance);
    }

    private static void customize(OpenAPI openApi, InstanceProperties instance) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        var components = openApi.getComponents();
        components.addSecuritySchemes(SCHEME, securityScheme(instance));
        components.addSchemas(TOKEN_SCHEMA, tokenSchema());
        components.addSchemas(ERROR_SCHEMA, errorSchema());
        components.addSchemas(METADATA_SCHEMA, metadataSchema());

        if (openApi.getPaths() == null) {
            openApi.setPaths(new Paths());
        }
        openApi.getPaths()
                .addPathItem(SecurityConfig.AUTHORIZE_PATH, new PathItem().get(authorize()).post(consent()))
                .addPathItem(SecurityConfig.TOKEN_PATH, new PathItem().post(token()))
                .addPathItem(SecurityConfig.REVOKE_PATH, new PathItem().post(revoke()))
                .addPathItem(SecurityConfig.METADATA_PATH, new PathItem().get(metadata()));
        openApi.addTagsItem(new Tag()
                .name(TAG)
                .description("Authorizing applications, following Mastodon's OAuth API, with the differences listed "
                        + "in each endpoint's description."));
    }

    private static SecurityScheme securityScheme(InstanceProperties instance) {
        var tokenUrl = instance.resolve(SecurityConfig.TOKEN_PATH).toString();
        var scopes = scopes();
        return new SecurityScheme()
                .type(SecurityScheme.Type.OAUTH2)
                .description("A token from `" + SecurityConfig.TOKEN_PATH + "`, sent as a bearer token. Register an "
                        + "application with `POST " + SecurityConfig.APP_REGISTRATION_PATH + "` first. A broad scope such "
                        + "as `read` also grants the granular scopes it lists.")
                .flows(new OAuthFlows()
                        .authorizationCode(new OAuthFlow()
                                .authorizationUrl(instance.resolve(SecurityConfig.AUTHORIZE_PATH).toString())
                                .tokenUrl(tokenUrl)
                                .scopes(scopes))
                        .clientCredentials(new OAuthFlow()
                                .tokenUrl(tokenUrl)
                                .scopes(scopes)));
    }

    private static Scopes scopes() {
        var scopes = new Scopes();
        for (var scope : OAuthScopes.supported()) {
            scopes.addString(scope, describe(scope));
        }
        return scopes;
    }

    private static String describe(String scope) {
        var sentences = new ArrayList<String>();
        var granted = OAuthScopes.grants(scope);
        if (!granted.isEmpty()) {
            sentences.add("Grants " + String.join(", ", granted) + ".");
        }
        switch (scope) {
            case "profile" -> sentences.add("Only `GET /api/v1/accounts/verify_credentials`.");
            case "push" -> sentences.add("Accepted, but nothing uses it yet.");
            case "follow" -> sentences.add("Deprecated.");
            default -> {
            }
        }
        if (OAuthScopes.isAdmin(scope)) {
            sentences.add("Only works for admins.");
        }
        return String.join(" ", sentences);
    }

    private static Operation authorize() {
        return new Operation()
                .tags(List.of(TAG))
                .operationId("oauthAuthorize")
                .summary("Ask a user to authorize an application")
                .description("""
                        Opened in the user's browser, not called by the application. The user logs in if they need \
                        to, and is asked to approve the scopes unless they already have. The code is then sent to \
                        `redirect_uri`, or shown on a page for `urn:ietf:wg:oauth:2.0:oob`. Each authorization issues \
                        a new token rather than reusing the application's existing one. `force_login`, `prompt`, and \
                        `lang` aren't supported.""")
                .addParametersItem(query("response_type", true, "Only `code` is supported.",
                        new StringSchema()._enum(List.of("code"))))
                .addParametersItem(query("client_id", true, "The application's `client_id`.", new StringSchema()))
                .addParametersItem(query("redirect_uri", true,
                        "One of the application's registered redirect URIs, or `urn:ietf:wg:oauth:2.0:oob`.",
                        new StringSchema()))
                .addParametersItem(query("scope", false, """
                        Space-separated scopes, which the application has to have registered for. Admin scopes are \
                        left out for users who aren't admins.""", new StringSchema()._default(OAuthScopes.DEFAULT_SCOPE)))
                .addParametersItem(query("state", false, "Sent back unchanged with the code.", new StringSchema()))
                .addParametersItem(query("code_challenge", false, "A PKCE challenge.", new StringSchema()))
                .addParametersItem(query("code_challenge_method", false, "Only `S256` is supported.",
                        new StringSchema()._enum(List.of("S256"))))
                .responses(authorizationResponses()
                        .addApiResponse("200", new ApiResponse()
                                .description("The consent page, or the code for `urn:ietf:wg:oauth:2.0:oob`.")
                                .content(html())));
    }

    private static Operation consent() {
        var properties = new LinkedHashMap<String, Schema<?>>();
        properties.put("client_id", new StringSchema());
        properties.put("state", new StringSchema().description("The `state` from the consent page."));
        properties.put("scope", strings().description(
                "The scopes approved, one value each. None denies the request, and removes any earlier approval."));
        var body = object(properties, "client_id", "state");
        return new Operation()
                .tags(List.of(TAG))
                .operationId("oauthConsent")
                .summary("Approve or deny an authorization")
                .description("""
                        Submitted by the consent page that `GET %s` shows, with the user's login session. It isn't \
                        for applications to call.""".formatted(SecurityConfig.AUTHORIZE_PATH))
                .requestBody(new RequestBody().required(true).content(form(body)))
                .responses(authorizationResponses()
                        .addApiResponse("200", new ApiResponse()
                                .description("The code, for `urn:ietf:wg:oauth:2.0:oob`.")
                                .content(html())));
    }

    private static ApiResponses authorizationResponses() {
        return new ApiResponses()
                .addApiResponse("302", new ApiResponse()
                        .description("""
                                To `redirect_uri` with `code` and `state`, or with `error`, `error_description`, \
                                and `state` if the request was refused or denied. To the login page if the user isn't \
                                logged in.""")
                        .addHeaderObject("Location", new Header().schema(new StringSchema())))
                .addApiResponse("400", new ApiResponse()
                        .description("""
                                An unknown client or a redirect URI it didn't register. These are never redirected. \
                                Also a denied or refused request for `urn:ietf:wg:oauth:2.0:oob`."""));
    }

    private static Operation token() {
        var properties = new LinkedHashMap<String, Schema<?>>();
        properties.put("grant_type", new StringSchema()._enum(List.of("authorization_code", "client_credentials")));
        properties.put("code", new StringSchema().description(
                "For `authorization_code`. A code can only be used once, and only by the client it was issued to."));
        properties.put("redirect_uri", new StringSchema().description(
                "For `authorization_code`, the same as in the authorization request."));
        properties.put("code_verifier", new StringSchema().description(
                "For `authorization_code`, if the authorization request had a `code_challenge`."));
        properties.put("scope", new StringSchema()
                ._default(OAuthScopes.DEFAULT_SCOPE)
                .description("For `client_credentials`, space-separated."));
        properties.put("client_id", new StringSchema());
        properties.put("client_secret", new StringSchema());
        var body = object(properties, "grant_type");
        return new Operation()
                .tags(List.of(TAG))
                .operationId("oauthToken")
                .summary("Get a token")
                .description("Issues a token for a user, with `authorization_code`, or for the application itself, "
                        + "with `client_credentials`. Tokens don't expire. " + CLIENT_AUTHENTICATION)
                .requestBody(new RequestBody().required(true).content(form(body)))
                .responses(new ApiResponses()
                        .addApiResponse("200", new ApiResponse()
                                .description("The token.")
                                .addHeaderObject("Cache-Control", new Header().schema(new StringSchema()._enum(
                                        List.of("no-store"))))
                                .content(json(ref(TOKEN_SCHEMA))))
                        .addApiResponse("400", error("""
                                `invalid_grant`, `invalid_request`, `invalid_scope`, or `unsupported_grant_type`. \
                                `invalid_scope` includes scopes the application didn't register for."""))
                        .addApiResponse("401", error("`invalid_client`: the client's credentials are missing or wrong.")));
    }

    private static Operation revoke() {
        var properties = new LinkedHashMap<String, Schema<?>>();
        properties.put("token", new StringSchema());
        properties.put("token_type_hint", new StringSchema()._enum(List.of("access_token")));
        properties.put("client_id", new StringSchema());
        properties.put("client_secret", new StringSchema());
        var body = object(properties, "token");
        return new Operation()
                .tags(List.of(TAG))
                .operationId("oauthRevoke")
                .summary("Revoke a token")
                .description("Revokes a token issued to the client. Revoking an unknown token succeeds, rather than "
                        + "returning the 403 that Mastodon documents. " + CLIENT_AUTHENTICATION)
                .requestBody(new RequestBody().required(true).content(form(body)))
                .responses(new ApiResponses()
                        .addApiResponse("200", new ApiResponse()
                                .description("An empty object, whether or not the token existed.")
                                .content(json(new ObjectSchema())))
                        .addApiResponse("400", error("`invalid_request`, such as a missing `token`."))
                        .addApiResponse("401", error("`invalid_client`: the client's credentials are missing or wrong."))
                        .addApiResponse("403", error("`unauthorized_client`: the token belongs to another client.")));
    }

    private static Operation metadata() {
        return new Operation()
                .tags(List.of(TAG))
                .operationId("oauthServerMetadata")
                .summary("Discover the OAuth endpoints and what they support")
                .description("RFC 8414 authorization server metadata, with Mastodon's `app_registration_endpoint`.")
                .responses(new ApiResponses().addApiResponse("200", new ApiResponse()
                        .description("The server's metadata.")
                        .content(json(ref(METADATA_SCHEMA)))));
    }

    /**
     * Mastodon's Token entity, as {@link OAuthResponses#accessToken} writes it. It has no {@code expires_in}.
     */
    private static Schema<?> tokenSchema() {
        var properties = new LinkedHashMap<String, Schema<?>>();
        properties.put("access_token", new StringSchema());
        properties.put("token_type", new StringSchema()._enum(List.of("Bearer")));
        properties.put("scope", new StringSchema().description("The granted scopes, space-separated."));
        properties.put("created_at", new IntegerSchema()
                .format("int64")
                .description("When the token was issued, in seconds since the Unix epoch."));
        var schema = object(properties, "access_token", "token_type", "scope", "created_at");
        schema.setDescription("Mastodon's Token entity. Tokens don't expire, so there's no `expires_in`.");
        return schema;
    }

    private static Schema<?> errorSchema() {
        var properties = new LinkedHashMap<String, Schema<?>>();
        properties.put("error", new StringSchema().description("An OAuth error code."));
        properties.put("error_description", new StringSchema());
        return object(properties, "error");
    }

    private static Schema<?> metadataSchema() {
        var properties = new LinkedHashMap<String, Schema<?>>();
        properties.put("issuer", new StringSchema().format("uri"));
        properties.put("authorization_endpoint", new StringSchema().format("uri"));
        properties.put("token_endpoint", new StringSchema().format("uri"));
        properties.put("revocation_endpoint", new StringSchema().format("uri"));
        properties.put("app_registration_endpoint", new StringSchema().format("uri"));
        properties.put("scopes_supported", strings());
        properties.put("response_types_supported", strings());
        properties.put("response_modes_supported", strings());
        properties.put("grant_types_supported", strings());
        properties.put("token_endpoint_auth_methods_supported", strings());
        properties.put("revocation_endpoint_auth_methods_supported", strings());
        properties.put("code_challenge_methods_supported", strings());
        return object(properties, properties.keySet().toArray(String[]::new));
    }

    /**
     * Builds an object from its properties, in their order. Swagger's own builder methods return raw types.
     */
    private static ObjectSchema object(LinkedHashMap<String, Schema<?>> properties, String... required) {
        var schema = new ObjectSchema();
        properties.forEach(schema::addProperty);
        schema.setRequired(List.of(required));
        return schema;
    }

    private static Parameter query(String name, boolean required, String description, Schema<?> schema) {
        return new Parameter().in("query").name(name).required(required).description(description).schema(schema);
    }

    private static ApiResponse error(String description) {
        return new ApiResponse().description(description).content(json(ref(ERROR_SCHEMA)));
    }

    private static Schema<?> ref(String name) {
        return new Schema<>().$ref("#/components/schemas/" + name);
    }

    private static ArraySchema strings() {
        return new ArraySchema().items(new StringSchema());
    }

    private static Content json(Schema<?> schema) {
        return new Content().addMediaType(JSON, new MediaType().schema(schema));
    }

    private static Content form(Schema<?> schema) {
        return new Content().addMediaType(FORM, new MediaType().schema(schema));
    }

    private static Content html() {
        return new Content().addMediaType(HTML, new MediaType().schema(new StringSchema()));
    }
}
