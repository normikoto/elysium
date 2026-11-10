package dev.biribiri.elysium.media;

import dev.biribiri.elysium.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fetches files from a real server, because the servlet container's table of types is only used there, and MockMvc has
 * its own.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaServingIntegrationTests {

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private MediaProperties media;

    @Test
    void servesEachKindAsTheTypeClientsAreTold() throws Exception {
        for (var entry : Map.of("flac", "audio/flac", "wav", "audio/wav", "m4a", "audio/mp4", "mov", "video/quicktime",
                "webm", "video/webm", "mp4", "video/mp4", "ogg", "audio/ogg", "mp3", "audio/mpeg", "webp", "image/webp")
                .entrySet()) {
            var name = UUID.randomUUID() + "." + entry.getKey();
            var file = Files.write(media.directory().resolve(name), new byte[]{1, 2, 3});
            try {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/media/" + name))
                        .build(), HttpResponse.BodyHandlers.discarding());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("Content-Type")).hasValue(entry.getValue());
                assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
                assertThat(response.headers().firstValue("Content-Security-Policy")).get().asString().contains("sandbox");
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }
}
