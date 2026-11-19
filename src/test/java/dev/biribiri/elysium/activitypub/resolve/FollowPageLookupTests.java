package dev.biribiri.elysium.activitypub.resolve;

import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FollowPageLookupTests {

    private static final URI TARGET = URI.create("https://local.example/users/alice");
    private static final URI WEBFINGER =
            URI.create("https://remote.example/.well-known/webfinger?resource=acct:bob@remote.example");

    private final FederationHttpClient http = mock();
    private final DomainBlocks blocks = mock();

    private FollowPageLookup lookup;

    @BeforeEach
    void setUp() {
        lookup = new FollowPageLookup(new WebFingerRequests(http, JsonMapper.builder().build()), blocks,
                new InstanceProperties(URI.create("https://local.example")));
    }

    @Test
    void fillsTheSubscribeTemplateWithTheEncodedTarget() throws Exception {
        answer(200, """
                {"links":[{"rel":"http://ostatus.org/schema/1.0/subscribe",
                  "template":"https://web.remote.example/authorize_interaction?uri={uri}"}]}
                """);

        assertThat(lookup.find("bob", "Remote.Example", TARGET)).contains(URI.create(
                "https://web.remote.example/authorize_interaction?uri=https%3A%2F%2Flocal.example%2Fusers%2Falice"));
    }

    @Test
    void prefersTheFollowIntent() throws Exception {
        answer(200, """
                {"links":[
                  {"rel":"http://ostatus.org/schema/1.0/subscribe","template":"https://remote.example/ostatus?uri={uri}"},
                  {"rel":"https://w3id.org/fep/3b86/Follow","template":"https://remote.example/follow?object={object}"}]}
                """);

        assertThat(lookup.find("bob", "remote.example", TARGET)).contains(URI.create(
                "https://remote.example/follow?object=https%3A%2F%2Flocal.example%2Fusers%2Falice"));
    }

    @Test
    void refusesWhatsntAnHttpsPage() throws Exception {
        for (var template : new String[]{"http://remote.example/follow?uri={uri}", "javascript:alert({uri})",
                "https://remote.example/follow?uri={other}", "https://remote.example/follow"}) {
            answer(200, """
                    {"links":[{"rel":"http://ostatus.org/schema/1.0/subscribe","template":"%s"}]}
                    """.formatted(template));

            assertThat(lookup.find("bob", "remote.example", TARGET)).as(template).isEmpty();
        }
    }

    @Test
    void findsNothingWithoutATemplateOrAnAnswer() throws Exception {
        answer(200, """
                {"links":[{"rel":"self","type":"application/activity+json","href":"https://remote.example/users/bob"}]}
                """);
        assertThat(lookup.find("bob", "remote.example", TARGET)).isEmpty();

        answer(404, "{}");
        assertThat(lookup.find("bob", "remote.example", TARGET)).isEmpty();

        when(http.get(eq(WEBFINGER), anyString(), any())).thenThrow(new IOException("down"));
        assertThat(lookup.find("bob", "remote.example", TARGET)).isEmpty();
    }

    @Test
    void doesntAskBlockedServersThisOneOrMalformedAddresses() throws Exception {
        when(blocks.isBlocked("blocked.example")).thenReturn(true);

        assertThat(lookup.find("bob", "blocked.example:8443", TARGET)).isEmpty();
        assertThat(lookup.find("bob", "local.example", TARGET)).isEmpty();
        assertThat(lookup.find("bob/..", "remote.example", TARGET)).isEmpty();
        assertThat(lookup.find("bob", "remote.example/path", TARGET)).isEmpty();
        verify(http, never()).get(any(), any(), any());
    }

    private void answer(int status, String body) throws IOException {
        when(http.get(eq(WEBFINGER), anyString(), any())).thenReturn(new FederationHttpClient.Response(
                status, "application/jrd+json", null, body.getBytes(StandardCharsets.UTF_8), null));
    }
}
