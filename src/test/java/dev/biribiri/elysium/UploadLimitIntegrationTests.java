package dev.biribiri.elysium;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Through a real server, since what matters is that Tomcat never parses a body that's refused. Tomcat reads all of a
 * refused body here, rather than closing the connection while the client is still sending it, so that the client always
 * gets the response.
 *
 * <p>With these settings, a request other than an upload to {@code /api/v2/media} or an emoji pack can be just over 4 MB,
 * and one with an emoji pack just over 5 MB.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "elysium.profiles.max-image-size=1KB",
        "elysium.custom-emojis.max-image-size=1KB",
        "elysium.custom-emojis.max-pack-size=1MB",
        "server.tomcat.max-swallow-size=-1"
})
class UploadLimitIntegrationTests {

    private static final int OVER_DEFAULT_LIMIT = 4 * 1024 * 1024 + 4096;
    private static final int OVER_PACK_LIMIT = 5 * 1024 * 1024 + 1024;

    @LocalServerPort
    private int port;

    @Test
    void refusesAChunkedUpload() throws Exception {
        var response = send(request("/api/v1/accounts/update_credentials", "PATCH",
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[100]))));

        assertThat(response.statusCode()).isEqualTo(411);
        assertThat(response.body()).contains("\"error\"");
    }

    @Test
    void refusesARequestOverItsEndpointsLimitBeforeCheckingTheToken() throws Exception {
        var response = send(request("/api/v1/accounts/update_credentials", "PATCH", body(OVER_DEFAULT_LIMIT)));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("Request body is too large");
    }

    @Test
    void letsBrowserClientsReadARefusal() throws Exception {
        var response = send(HttpRequest.newBuilder(uri("/api/v1/accounts/update_credentials"))
                .header("Content-Type", "multipart/form-data; boundary=x")
                .header("Origin", "https://client.example")
                .method("PATCH", body(OVER_DEFAULT_LIMIT))
                .build());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue("*");
    }

    @Test
    void givesMediaUploadsTheirOwnLimit() throws Exception {
        var response = send(request("/api/v2/media", "POST", body(OVER_DEFAULT_LIMIT)));

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void givesEmojiPacksTheirOwnLimit() throws Exception {
        assertThat(send(request("/admin/custom-emojis/import", "POST", body(OVER_DEFAULT_LIMIT))).statusCode())
                .isNotIn(411, 413);
        assertThat(send(request("/admin/custom-emojis/import", "POST", body(OVER_PACK_LIMIT))).statusCode())
                .isEqualTo(413);
    }

    @Test
    void showsTheErrorPageForAPage() throws Exception {
        var response = send(HttpRequest.newBuilder(uri("/settings"))
                .header("Content-Type", "multipart/form-data; boundary=x")
                .header("Accept", "text/html")
                .POST(body(OVER_DEFAULT_LIMIT))
                .build());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("The upload is too large.");
    }

    @Test
    void acceptsOtherChunkedBodies() throws Exception {
        var response = send(HttpRequest.newBuilder(uri("/api/v1/statuses"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream("{}".getBytes())))
                .build());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    private HttpRequest request(String path, String method, HttpRequest.BodyPublisher body) {
        return HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "multipart/form-data; boundary=x")
                .method(method, body)
                .build();
    }

    private static HttpRequest.BodyPublisher body(int length) {
        return HttpRequest.BodyPublishers.ofByteArray(new byte[length]);
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
