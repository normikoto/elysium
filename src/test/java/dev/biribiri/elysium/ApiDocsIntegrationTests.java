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
    void doesNotDocumentActuatorEndpoints() throws Exception {
        mockMvc.perform(get("/api-docs")).andExpect(jsonPath("$.paths['/actuator']").doesNotExist());
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

    private JsonNode document() throws Exception {
        return json.readTree(mockMvc.perform(get("/api-docs")).andReturn().getResponse().getContentAsString());
    }
}
