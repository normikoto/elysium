package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient.Response;
import dev.biribiri.elysium.activitypub.http.HostRequests;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityDocumentsTests {

    private static final URI NOTE = URI.create("https://Remote.example/notes/1");
    private static final int MAX_FETCHES = FederationHttpClient.MAX_CONNECTIONS_PER_HOST;

    private final SignedFetcher http = mock();
    private final HostRequests hostRequests = new HostRequests();
    private final ActivityDocuments documents = new ActivityDocuments(http, JsonMapper.builder().build(), hostRequests);

    @Test
    void countsAFetchWhileItRuns() throws Exception {
        when(http.get(eq(NOTE), any())).thenAnswer(invocation -> {
            assertThat(hostRequests.tryStartDelivery("remote.example")).isTrue();
            // One fetch and one delivery are running, so a host with room for one more delivery has none.
            for (var i = 2; i < HostRequests.MAX_DELIVERIES_PER_HOST; i++) {
                assertThat(hostRequests.tryStartDelivery("remote.example")).isTrue();
            }
            assertThat(hostRequests.tryStartDelivery("remote.example")).isFalse();
            return ok();
        });

        assertThat(documents.fetch(NOTE).path("id").asString()).isEqualTo(NOTE.toString());
    }

    @Test
    void releasesItsSlotWhenAFetchFails() throws Exception {
        when(http.get(eq(NOTE), any())).thenThrow(new IOException("reset"));

        for (var i = 0; i < MAX_FETCHES + 1; i++) {
            assertThatThrownBy(() -> documents.fetch(NOTE))
                    .isInstanceOfSatisfying(DocumentFetchException.class, e -> assertThat(e.getMessage()).contains("reset"));
        }
    }

    @Test
    void refusesAFetchWhenTheHostHasNoRoom() throws Exception {
        for (var i = 0; i < MAX_FETCHES; i++) {
            hostRequests.tryStartFetch("remote.example");
        }

        assertThatThrownBy(() -> documents.fetch(NOTE))
                .isInstanceOfSatisfying(DocumentFetchException.class, e -> assertThat(e.isTemporary()).isTrue());
        verify(http, never()).get(any(), any());
    }

    private static Response ok() {
        var body = "{\"id\":\"" + NOTE + "\",\"type\":\"Note\"}";
        return new Response(200, "application/activity+json", null, body.getBytes(StandardCharsets.UTF_8), null);
    }
}
