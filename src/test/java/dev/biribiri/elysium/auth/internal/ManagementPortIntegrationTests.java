package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs real servers, because the management port is a server of its own, which MockMvc doesn't start.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class ManagementPortIntegrationTests {

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @Test
    void servesMetricsWithoutCredentials() throws Exception {
        var response = get(managementPort, "/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("elysium_jobs_queued{state=\"due\",task=\"activity-delivery\"}")
                .contains("elysium_jobs_oldest_due_seconds{task=\"activity-delivery\"}")
                .contains("dbscheduler_task_completions");
    }

    @Test
    void servesHealth() throws Exception {
        assertThat(get(managementPort, "/actuator/health").statusCode()).isEqualTo(200);
    }

    @Test
    void keepsHealthOnTheMainPort() throws Exception {
        for (var path : new String[] {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            var response = get(port, path);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path).contains("\"status\":\"UP\"");
        }
    }

    @Test
    void refusesOtherEndpoints() throws Exception {
        assertThat(get(managementPort, "/actuator").statusCode()).isEqualTo(403);
        assertThat(get(managementPort, "/actuator/env").statusCode()).isIn(403, 404);
    }

    @Test
    void keepsMetricsOffTheMainPort() throws Exception {
        assertThat(get(port, "/actuator/prometheus").statusCode()).isIn(403, 404);
    }

    private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
