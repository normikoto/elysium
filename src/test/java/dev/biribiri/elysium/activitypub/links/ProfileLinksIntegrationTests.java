package dev.biribiri.elysium.activitypub.links;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.inbound.RemoteActors;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.ProfileChange;
import dev.biribiri.elysium.actor.ProfileField;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.actor.RemoteProfileLinksToVerify;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the real scheduler, with the pages that fields link to served by a mock client, since the real one only fetches
 * HTTPS from public addresses.
 */
@Import({TestcontainersConfiguration.class, ProfileLinksIntegrationTests.Pages.class})
@SpringBootTest(properties = {"db-scheduler.polling-interval=PT0.1S", "db-scheduler.heartbeat-interval=PT1S"})
@AutoConfigureMockMvc
@RecordApplicationEvents
class ProfileLinksIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private ActorService actors;
    @Autowired
    private ProfileService profiles;
    @Autowired
    private RemoteActors remoteActors;
    @Autowired
    private JsonMapper json;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApplicationEvents events;
    private static final Map<URI, FederationHttpClient.Response> PAGES = new ConcurrentHashMap<>();
    private static final FederationHttpClient.Response NOT_FOUND = new FederationHttpClient.Response(404, null, null, new byte[0], null);

    @Autowired
    private FederationHttpClient http;

    private Actor local;

    @BeforeEach
    void setUp() {
        PAGES.clear();
        clearInvocations(http);
        var name = "l" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        actors.createLocalIfAbsent(name, actor -> {
        });
        local = actors.findLocalByUsername(name).orElseThrow();
    }

    @Test
    void verifiesAFieldWhosePageLinksBack() throws IOException {
        page("https://mine.test/", "<a rel=\"me noopener\" href=\"" + local.uri() + "\">me</a>");
        page("https://other.test/", "<a href=\"" + local.uri() + "\">not rel=me</a>");
        redirect("https://moved.test/", "https://www.moved.test/about");
        page("https://www.moved.test/about", "<link rel=\"me\" href=\"" + local.uri() + "/\">");

        profiles.update(local.id(), ProfileChange.NONE.withFields(List.of(
                new ProfileChange.NewField("Site", "https://mine.test/"),
                new ProfileChange.NewField("Other", "https://other.test/"),
                new ProfileChange.NewField("Moved", "https://moved.test/"),
                new ProfileChange.NewField("Text", "not a link"))));

        await().atMost(WAIT).until(() -> fields().get(0).verifiedAt() != null && fields().get(2).verifiedAt() != null);
        verify(http, timeout(WAIT.toMillis())).get(eq(URI.create("https://other.test/")), anyString(), any());
        assertThat(fields().get(1).verifiedAt()).isNull();
        assertThat(fields().get(3).verifiedAt()).isNull();
    }

    @Test
    void keepsAVerificationWhileTheValueStaysTheSame() throws IOException {
        page("https://kept.test/", "<a rel=\"me\" href=\"" + local.uri() + "\">me</a>");
        profiles.update(local.id(), ProfileChange.NONE.withFields(List.of(new ProfileChange.NewField("Site", "https://kept.test/"))));
        await().atMost(WAIT).until(() -> fields().getFirst().verifiedAt() != null);
        var verifiedAt = fields().getFirst().verifiedAt();

        profiles.update(local.id(), ProfileChange.NONE.withFields(List.of(
                new ProfileChange.NewField("Renamed", "https://kept.test/"),
                new ProfileChange.NewField("New", "https://new.test/"))));

        assertThat(fields().getFirst().verifiedAt()).isEqualTo(verifiedAt);
        assertThat(fields().get(1).verifiedAt()).isNull();
    }

    @Test
    void verifiesAMatchingRemoteLinkAndClearsVerificationWhenTheLabelChanges() throws Exception {
        var actor = URI.create("https://profiles.test/users/alice");
        var profile = URI.create("https://profiles.test/@alice");
        var site = "https://alice.test/about";
        page(site, "<a rel=\"me\" href=\"" + profile + "\">Alice</a>");
        var field = "<a href=\"" + site + "\"><span class=\"invisible\">https://</span>alice.test/about</a>";
        actorDocument(actor, profile, field);

        var remote = remoteActors.fetchOrRefresh(actor);

        await().atMost(WAIT).until(() -> profiles.find(remote.id()).fields().getFirst().verifiedAt() != null);
        var checked = profiles.find(remote.id()).fields().getFirst();
        mockMvc.perform(get("/api/v1/accounts/{id}", remote.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[0].verified_at").isNotEmpty())
                .andExpect(jsonPath("$.fields[0].value").value(org.hamcrest.Matchers.containsString(">" + site + "</a>")));

        remoteActors.refresh(actor).orElseThrow();
        assertThat(profiles.find(remote.id()).fields().getFirst().verifiedAt()).isEqualTo(checked.verifiedAt());

        actorDocument(actor, profile, "<a href=\"" + site + "\">https://other-alice.test/about</a>");
        remoteActors.refresh(actor).orElseThrow();
        assertThat(profiles.find(remote.id()).fields().getFirst().verifiedAt()).isNull();
        assertThat(profiles.markVerified(remote.id(), 0, checked, profile, Instant.now())).isFalse();
    }

    @Test
    void ignoresAnUnrelatedProfileUrlAndLinksWithinOtherText() throws IOException {
        var actor = URI.create("https://elsewhere.test/users/bob");
        var claimedProfile = URI.create("https://unrelated.test/users/bob");
        page("https://unrelated-blog.test/", "<link rel=\"me\" href=\"" + claimedProfile + "\">");
        page("https://embedded-link.test/", "<link rel=\"me\" href=\"" + actor + "\">");
        page("https://bobs-site.test/", "<link rel=\"me\" href=\"" + actor + "\">");
        actorDocument(actor, claimedProfile,
                "<a href=\"https://unrelated-blog.test/\">https://unrelated-blog.test/</a>",
                "Try <a href=\"https://embedded-link.test/\">https://embedded-link.test/</a>",
                "https://bobs-site.test/");

        var remote = remoteActors.fetchOrRefresh(actor);

        await().atMost(WAIT).until(() -> profiles.find(remote.id()).fields().getLast().verifiedAt() != null);
        assertThat(profiles.find(remote.id()).fields().subList(0, 2)).allMatch(field -> field.verifiedAt() == null);
        verify(http, never()).get(eq(URI.create("https://embedded-link.test/")), anyString(), any());
    }

    @Test
    void repeatedRemoteUpdatesDoNotKeepQueueingChecksForUnverifiedLinks() throws IOException {
        var actor = URI.create("https://updates.test/users/alice");
        var site = "https://no-backlink.test/";
        actorDocument(actor, actor, site);
        var remote = remoteActors.fetchOrRefresh(actor);
        verify(http, timeout(WAIT.toMillis())).get(eq(URI.create(site)), eq("text/html"), any());

        for (var i = 0; i < 5; i++) {
            actorDocument(actor, actor, site, "https://new-field.test/" + i);
            remoteActors.refresh(actor).orElseThrow();
        }

        assertThat(events.stream(RemoteProfileLinksToVerify.class).map(RemoteProfileLinksToVerify::actorId))
                .containsExactly(remote.id());
    }

    private void actorDocument(URI actor, URI profile, String... values) {
        var document = json.createObjectNode()
                .put("id", actor.toString())
                .put("type", "Person")
                .put("preferredUsername", "remote")
                .put("inbox", actor + "/inbox")
                .put("url", profile.toString());
        var attachments = document.putArray("attachment");
        for (var value : values) {
            attachments.addObject().put("type", "PropertyValue").put("name", "Website").put("value", value);
        }
        PAGES.put(actor, new FederationHttpClient.Response(200, "application/activity+json", null,
                json.writeValueAsBytes(document), null));
    }

    private List<ProfileField> fields() {
        return profiles.find(local.id()).fields();
    }

    private void page(String uri, String html) {
        PAGES.put(URI.create(uri), new FederationHttpClient.Response(
                200, "text/html", null, ("<!doctype html><html><head></head><body>" + html + "</body></html>")
                .getBytes(StandardCharsets.UTF_8), null));
    }

    private void redirect(String uri, String location) {
        PAGES.put(URI.create(uri), new FederationHttpClient.Response(301, null, null, new byte[0], location));
    }

    /**
     * Stubbed once, before any thread can call it, and answered from {@link #PAGES} rather than stubbing the mock in each
     * test. A test's last profile update leaves a check running after it ends, and Mockito's stubbing isn't safe while
     * another thread calls the mock, so stubbing for the next test could attach a page to that call instead.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Pages {

        @Bean
        @Primary
        FederationHttpClient pagesHttpClient() throws IOException {
            var http = mock(FederationHttpClient.class);
            when(http.get(any(), anyString(), any()))
                    .thenAnswer(invocation -> PAGES.getOrDefault(invocation.<URI>getArgument(0), NOT_FOUND));
            return http;
        }
    }
}
