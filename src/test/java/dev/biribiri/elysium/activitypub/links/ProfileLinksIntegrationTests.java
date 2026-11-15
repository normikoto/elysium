package dev.biribiri.elysium.activitypub.links;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.ProfileChange;
import dev.biribiri.elysium.actor.ProfileField;
import dev.biribiri.elysium.actor.ProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Runs the real scheduler, with the pages that fields link to served by a mock client, since the real one only fetches
 * HTTPS from public addresses.
 */
@Import({TestcontainersConfiguration.class, ProfileLinksIntegrationTests.Pages.class})
@SpringBootTest(properties = {"db-scheduler.polling-interval=PT0.1S", "db-scheduler.heartbeat-interval=PT1S"})
class ProfileLinksIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private ActorService actors;
    @Autowired
    private ProfileService profiles;
    private static final Map<URI, FederationHttpClient.Response> PAGES = new ConcurrentHashMap<>();
    private static final FederationHttpClient.Response NOT_FOUND = new FederationHttpClient.Response(404, null, null, new byte[0], null);

    @Autowired
    private FederationHttpClient http;

    private Actor local;

    @BeforeEach
    void setUp() {
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
     * Answers from {@link #PAGES} rather than stubbing the mock in each test. A test's last profile update leaves a check
     * running after it ends, and Mockito's stubbing isn't safe while another thread calls the mock, so stubbing for the next
     * test could attach a page to that call instead.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Pages {

        @Bean
        @Primary
        FederationHttpClient pagesHttpClient() {
            return mock(FederationHttpClient.class, invocation -> invocation.getMethod().getName().equals("get")
                    ? PAGES.getOrDefault(invocation.<URI>getArgument(0), NOT_FOUND)
                    : Answers.RETURNS_DEFAULTS.answer(invocation));
        }
    }
}
