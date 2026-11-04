package dev.biribiri.elysium;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void servesTheOpenApiDocumentPublicly() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.openapi").isString())
                .andExpect(jsonPath("$.paths['/api/v1/apps']").exists())
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
    void theDefaultPathsAreClosed() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isForbidden());
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isForbidden());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isForbidden());
    }
}
