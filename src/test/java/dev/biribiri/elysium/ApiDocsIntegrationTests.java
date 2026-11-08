package dev.biribiri.elysium;

import dev.biribiri.elysium.auth.internal.OAuthScopes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ApiDocsIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper json;

    @Test
    void servesTheOpenApiDocumentPublicly() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.openapi").isString())
                .andExpect(jsonPath("$.paths['/api/v1/apps']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/domain_blocks']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/domain_blocks/{id}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/accounts/verify_credentials']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/accounts/{id}/statuses']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/statuses']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/statuses/{id}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/timelines/public']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/instance']").exists());
    }

    @Test
    void documentsEveryFormatForCreatingAStatus() throws Exception {
        var content = "$.paths['/api/v1/statuses'].post.requestBody.content";
        mockMvc.perform(get("/api-docs"))
                .andExpect(jsonPath(content + "['application/json']").exists())
                .andExpect(jsonPath(content + "['application/x-www-form-urlencoded']").exists())
                .andExpect(jsonPath(content + "['multipart/form-data']").exists());
    }

    @Test
    void servesTheOpenApiDocumentAsYaml() throws Exception {
        mockMvc.perform(get("/api-docs.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("openapi:")));
    }

    @Test
    void doesNotDocumentTheJobUi() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("db-scheduler"))));
    }

    @Test
    void servesSwaggerUiAndItsAssetsPublicly() throws Exception {
        var location = mockMvc.perform(get("/api-docs/ui"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(location).contains("/api-docs/swagger-ui/index.html");

        mockMvc.perform(get(URI.create(location)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("swagger-ui-bundle.js")));
        mockMvc.perform(get("/api-docs/swagger-ui/swagger-ui-bundle.js")).andExpect(status().isOk());
        mockMvc.perform(get("/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/api-docs"));
    }

    @Test
    void documentsTheOAuthEndpoints() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(jsonPath("$.paths['/oauth/authorize'].get").exists())
                .andExpect(jsonPath("$.paths['/oauth/authorize'].post").exists())
                .andExpect(jsonPath("$.paths['/oauth/token'].post").exists())
                .andExpect(jsonPath("$.paths['/oauth/revoke'].post").exists())
                .andExpect(jsonPath("$.paths['/.well-known/oauth-authorization-server'].get").exists());
    }

    @Test
    void documentsMastodonsTokenEntity() throws Exception {
        var token = "$.components.schemas.Token.properties";
        var requestBody = "$.paths['/oauth/token'].post.requestBody.content";
        mockMvc.perform(get("/api-docs"))
                .andExpect(jsonPath(token + ".created_at.type").value("integer"))
                .andExpect(jsonPath(token + ".access_token").exists())
                .andExpect(jsonPath(token + ".expires_in").doesNotExist())
                .andExpect(jsonPath("$.paths['/oauth/token'].post.responses['200'].content['application/json'].schema"
                        + "['$ref']").value("#/components/schemas/Token"))
                .andExpect(jsonPath(requestBody + "['application/x-www-form-urlencoded']").exists())
                .andExpect(jsonPath(requestBody + "['application/json']").doesNotExist())
                .andExpect(jsonPath("$.paths['/oauth/revoke'].post.responses['403']").exists());
    }

    @Test
    void listsEverySupportedScope() throws Exception {
        var document = document();
        for (var flow : List.of("authorizationCode", "clientCredentials")) {
            var scopes = document.at("/components/securitySchemes/oauth2/flows/" + flow + "/scopes");
            assertThat(scopes.propertyNames()).containsExactlyElementsOf(OAuthScopes.supported());
        }
    }

    @Test
    void documentsTheScopesEachEndpointNeeds() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/statuses'].post.security[0].oauth2[0]").value("write:statuses"))
                .andExpect(jsonPath("$.paths['/api/v1/statuses'].post.security.length()").value(1))
                .andExpect(jsonPath("$.paths['/api/v1/statuses/{id}'].get.security[0].oauth2[0]")
                        .value("read:statuses"))
                .andExpect(jsonPath("$.paths['/api/v1/statuses/{id}'].get.security[1]").isEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/accounts/verify_credentials'].get.security[*].oauth2[0]")
                        .value(containsInAnyOrder("profile", "read:accounts")))
                .andExpect(jsonPath("$.paths['/api/v1/apps/verify_credentials'].get.security[0].oauth2").isEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/admin/domain_blocks'].get.security[0].oauth2[0]")
                        .value("admin:read:domain_blocks"))
                .andExpect(jsonPath("$.paths['/api/v1/instance'].get.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/apps'].post.security").doesNotExist());
    }

    /**
     * Endpoints that work with no token at all. Any other API endpoint that's added has to say what it needs.
     */
    @Test
    void everyApiEndpointThatNeedsATokenSaysSo() throws Exception {
        var noToken = Set.of("post /api/v1/apps", "get /api/v1/instance");
        var undocumented = new ArrayList<String>();
        for (var path : document().get("paths").properties()) {
            if (!path.getKey().startsWith("/api/")) {
                continue;
            }
            for (var operation : path.getValue().properties()) {
                var name = operation.getKey() + " " + path.getKey();
                var security = operation.getValue().path("security");
                if (!noToken.contains(name) && security.isEmpty()) {
                    undocumented.add(name);
                }
            }
        }
        assertThat(undocumented).isEmpty();
    }

    @Test
    void describesEveryEndpoint() throws Exception {
        var undescribed = new ArrayList<String>();
        var operationIds = new ArrayList<String>();
        for (var path : document().get("paths").properties()) {
            for (var operation : path.getValue().properties()) {
                var name = operation.getKey() + " " + path.getKey();
                var op = operation.getValue();
                var linksToMastodon = op.path("externalDocs").path("url").asString("")
                        .startsWith("https://docs.joinmastodon.org/methods/");
                if (op.path("summary").asString("").isEmpty()
                        || (path.getKey().startsWith("/api/") && !linksToMastodon)) {
                    undescribed.add(name);
                }
                operationIds.add(op.path("operationId").asString(""));
            }
        }
        assertThat(undescribed).isEmpty();
        // springdoc adds a number to repeated IDs.
        assertThat(operationIds).doesNotHaveDuplicates().noneMatch(id -> id.isEmpty() || id.matches(".*_\\d+"));
    }

    @Test
    void describesTheServer() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(jsonPath("$.info.title").value("Elysium"))
                .andExpect(jsonPath("$.info.description").value(containsString("Mastodon")))
                .andExpect(jsonPath("$.tags[0].name").value("Apps"))
                .andExpect(jsonPath("$.tags[1].name").value("OAuth"));
    }

    @Test
    void namesSchemasAfterMastodonsEntities() throws Exception {
        var schemas = document().get("components").get("schemas");
        assertThat(schemas.propertyNames()).contains(
                "Account", "CredentialAccount", "Status", "Relationship", "Application", "Instance", "Search",
                "AdminAccount", "AdminDomainBlock", "Error");
    }

    @Test
    void givesEveryApiErrorTheErrorBody() throws Exception {
        var wrong = new ArrayList<String>();
        for (var path : document().get("paths").properties()) {
            if (!path.getKey().startsWith("/api/")) {
                continue;
            }
            for (var operation : path.getValue().properties()) {
                for (var response : operation.getValue().path("responses").properties()) {
                    var schema = response.getValue().at("/content/application~1json/schema/$ref").asString("");
                    if (response.getKey().startsWith("4") && !schema.equals("#/components/schemas/Error")) {
                        wrong.add(operation.getKey() + " " + path.getKey() + " " + response.getKey());
                    }
                }
            }
        }
        assertThat(wrong).isEmpty();
        mockMvc.perform(get("/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/statuses'].post.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/statuses'].post.responses['403']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/apps/verify_credentials'].get.responses['403']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/instance'].get.responses['401']").doesNotExist());
    }

    @Test
    void documentsTheFederationEndpoints() throws Exception {
        var actor = "$.paths['/users/{username}'].get";
        mockMvc.perform(get("/api-docs"))
                .andExpect(jsonPath("$.paths['/.well-known/webfinger'].get.tags[0]").value("WebFinger"))
                .andExpect(jsonPath("$.paths['/.well-known/nodeinfo'].get").exists())
                .andExpect(jsonPath("$.paths['/nodeinfo/2.0'].get").exists())
                .andExpect(jsonPath("$.paths['/nodeinfo/2.1'].get").exists())
                .andExpect(jsonPath("$.paths['/actor'].get").exists())
                .andExpect(jsonPath("$.paths['/users/{username}/statuses/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/users/{username}/followers'].get").exists())
                .andExpect(jsonPath("$.paths['/users/{username}/following'].get").exists())
                .andExpect(jsonPath("$.paths['/users/{username}/outbox'].get").exists())
                .andExpect(jsonPath("$.paths['/actor/outbox'].get").exists())
                .andExpect(jsonPath(actor + ".tags[0]").value("ActivityPub"))
                .andExpect(jsonPath(actor + ".responses['200'].content['application/activity+json'].schema['$ref']")
                        .value("#/components/schemas/Person"))
                .andExpect(jsonPath(actor + ".responses['200'].content['text/html']").exists())
                .andExpect(jsonPath(actor + ".responses['404'].content").doesNotExist())
                .andExpect(jsonPath(actor + ".responses['410'].content['application/activity+json'].schema['$ref']")
                        .value("#/components/schemas/Tombstone"));
    }

    @Test
    void documentsWhatEveryInboxTakes() throws Exception {
        for (var path : List.of("/users/{username}/inbox", "/inbox", "/actor/inbox")) {
            var inbox = "$.paths['" + path + "'].post";
            mockMvc.perform(get("/api-docs"))
                    .andExpect(jsonPath(inbox + ".requestBody.content['application/activity+json']").exists())
                    .andExpect(jsonPath(inbox + ".parameters[?(@.name == 'Signature')].in").value("header"))
                    .andExpect(jsonPath(inbox + ".responses['202']").exists())
                    .andExpect(jsonPath(inbox + ".responses['200']").doesNotExist())
                    .andExpect(jsonPath(inbox + ".responses['429'].headers['Retry-After']").exists());
        }
        mockMvc.perform(get("/api-docs"))
                .andExpect(jsonPath("$.paths['/users/{username}/inbox'].post.responses['410']").exists())
                .andExpect(jsonPath("$.paths['/inbox'].post.responses['410']").doesNotExist());
    }

    @Test
    void leavesOutHtmlPages() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(jsonPath("$.paths['/login']").doesNotExist())
                .andExpect(jsonPath("$.paths['/signup']").doesNotExist())
                .andExpect(jsonPath("$.paths['/oauth/consent']").doesNotExist());
    }

    @Test
    void swaggerUiSignsInWithPkce() throws Exception {
        mockMvc.perform(get("/api-docs/swagger-ui/swagger-initializer.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"usePkceWithAuthorizationCodeGrant\":true")));
    }

    @Test
    void theDefaultPathsAreClosed() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isForbidden());
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isForbidden());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isForbidden());
    }

    @Test
    void documentsInboxPathsSchemasAndScopes() throws Exception {
        var document = document();
        var expected = Map.ofEntries(
                Map.entry("/api/v1/notifications", "get"),
                Map.entry("/api/v1/notifications/{id}", "get"),
                Map.entry("/api/v1/notifications/clear", "post"),
                Map.entry("/api/v1/notifications/{id}/dismiss", "post"),
                Map.entry("/api/v1/notifications/unread_count", "get"),
                Map.entry("/api/v2/notifications", "get"),
                Map.entry("/api/v2/notifications/{group_key}", "get"),
                Map.entry("/api/v2/notifications/{group_key}/accounts", "get"),
                Map.entry("/api/v2/notifications/{group_key}/dismiss", "post"),
                Map.entry("/api/v2/notifications/unread_count", "get"),
                Map.entry("/api/v1/conversations", "get"),
                Map.entry("/api/v1/conversations/{id}", "delete"),
                Map.entry("/api/v1/conversations/{id}/read", "post"),
                Map.entry("/api/v1/markers", "post"));
        for (var entry : expected.entrySet()) {
            var operation = document.get("paths").get(entry.getKey()).get(entry.getValue());
            assertThat(operation).as(entry.toString()).isNotNull();
            var notification = entry.getKey().contains("notifications");
            var read = entry.getValue().equals("get");
            var scope = notification ? (read ? "read:notifications" : "write:notifications")
                    : entry.getKey().contains("markers") ? "write:statuses" : read ? "read:statuses" : "write:conversations";
            assertThat(operation.at("/security/0/oauth2/0").asString()).isEqualTo(scope);
            assertThat(operation.path("responses").propertyNames()).contains("200", "401", "403", "422");
        }
        assertThat(document.get("components").get("schemas").propertyNames()).contains(
                "Notification", "NotificationGroup", "GroupedNotificationsResults", "Conversation", "Marker", "UnreadCount", "PartialAccountWithAvatar");
        assertThat(document.get("paths").get("/api/v1/markers").get("get").at("/security/0/oauth2/0").asString()).isEqualTo("read:statuses");
    }

    @Test
    void documentsInboxPagingAndMarkerRequestFormats() throws Exception {
        var document = document();
        for (var path : List.of("/api/v1/notifications", "/api/v2/notifications", "/api/v1/conversations")) {
            var operation = document.get("paths").get(path).get("get");
            assertThat(operation.at("/responses/200/headers/Link").isMissingNode()).isFalse();
            for (var parameter : operation.path("parameters")) {
                assertThat(parameter.path("in").asString()).as(path + " " + parameter).isEqualTo("query");
            }
        }
        var marker = document.get("paths").get("/api/v1/markers").get("post");
        assertThat(marker.at("/requestBody/content").propertyNames()).contains(
                "application/json", "application/x-www-form-urlencoded", "multipart/form-data");
        assertThat(marker.path("responses").propertyNames()).contains("409", "413");
    }

    private JsonNode document() throws Exception {
        return json.readTree(mockMvc.perform(get("/api-docs")).andReturn().getResponse().getContentAsString());
    }
}
