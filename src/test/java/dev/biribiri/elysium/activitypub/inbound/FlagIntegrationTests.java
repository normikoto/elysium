package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.moderation.DomainBlocks;
import dev.biribiri.elysium.moderation.Report;
import dev.biribiri.elysium.moderation.ReportPage;
import dev.biribiri.elysium.moderation.Reports;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Reports from other servers. The handler is called directly with no worker running, as in {@link BlockIntegrationTests}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://flag.example", "elysium.jobs.worker-enabled=false"})
class FlagIntegrationTests {

    @Autowired
    private FlagHandler handler;
    @Autowired
    private ActorService actors;
    @Autowired
    private StatusService statuses;
    @Autowired
    private Reports reports;
    @Autowired
    private DomainBlocks domainBlocks;
    @Autowired
    private JsonMapper json;
    @MockitoBean
    private ActivityDocuments documents;

    private Actor local;
    private Actor other;
    private String host;
    private String reporterUri;

    @BeforeEach
    void setUp() throws Exception {
        local = newLocal();
        other = newLocal();
        host = "h" + UUID.randomUUID() + ".test";
        reporterUri = "https://" + host + "/actor";
        when(documents.fetch(eq(URI.create(reporterUri)))).thenReturn(json.readTree("""
                {"id":"%s","type":"Application","preferredUsername":"%s","inbox":"%s/inbox"}
                """.formatted(reporterUri, host, reporterUri)));
    }

    @Test
    void storesAReportFromAnInstanceActor() {
        var first = publish(local, "First");
        var second = publish(local, "Second");

        handler.handle(flag(newId(), "Spam, please look", local.uri(), first.uri(), second.uri()));

        var report = only(reportsAbout(local));
        var reporter = actors.findByUri(URI.create(reporterUri)).orElseThrow();
        assertThat(report.reporterId()).isEqualTo(reporter.id());
        assertThat(report.comment()).isEqualTo("Spam, please look");
        assertThat(report.statusIds()).containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(report.isRemote()).isTrue();
        assertThat(report.forwarded()).isFalse();
    }

    @Test
    void aFlagOfStatusesAloneIsAboutTheirAuthors() {
        var mine = publish(local, "Mine");
        var theirs = publish(other, "Theirs");

        handler.handle(flag(newId(), "", mine.uri(), theirs.uri()));

        assertThat(only(reportsAbout(local)).statusIds()).containsExactly(mine.id());
        assertThat(only(reportsAbout(other)).statusIds()).containsExactly(theirs.id());
    }

    @Test
    void aRepeatIsIgnored() {
        var id = newId();

        handler.handle(flag(id, "Once", local.uri()));
        handler.handle(flag(id, "Twice", local.uri()));

        assertThat(only(reportsAbout(local)).comment()).isEqualTo("Once");
    }

    @Test
    void ignoresWhatIsntHere() {
        handler.handle(flag(newId(), "Elsewhere", URI.create("https://" + host + "/users/someone"),
                URI.create("https://flag.example/users/nobody"), URI.create("https://flag.example/statuses/" + UUID.randomUUID())));

        assertThat(actors.findByUri(URI.create(reporterUri))).isEmpty();
    }

    @Test
    void cutsALongCommentShort() {
        handler.handle(flag(newId(), "x".repeat(Reports.MAX_COMMENT_LENGTH + 50), local.uri()));

        assertThat(only(reportsAbout(local)).comment()).hasSize(Reports.MAX_COMMENT_LENGTH);
    }

    @Test
    void refusesABlockedServer() {
        domainBlocks.create(host, null);

        handler.handle(flag(newId(), "Blocked", local.uri()));

        assertThat(reportsAbout(local)).isEmpty();
    }

    @Test
    void refusesASuspendedReporter() {
        handler.handle(flag(newId(), "First", other.uri()));
        actors.suspend(actors.findByUri(URI.create(reporterUri)).orElseThrow().id());

        assertThatThrownBy(() -> handler.handle(flag(newId(), "Suspended", local.uri())))
                .isInstanceOf(PermanentJobFailure.class);
        assertThat(reportsAbout(local)).isEmpty();
    }

    private Actor newLocal() {
        var name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        actors.createLocalIfAbsent(name, actor -> {
        });
        return actors.findLocalByUsername(name).orElseThrow();
    }

    private Status publish(Actor author, String text) {
        return statuses.create(author.id(), new NewStatus(text, null, null, Visibility.PUBLIC, false, null));
    }

    private List<Report> reportsAbout(Actor target) {
        return reports.list(new ReportPage(false, null, target.id(), ReportPage.MAX_LIMIT, null, null, null));
    }

    private static Report only(List<Report> found) {
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private String newId() {
        return "https://" + host + "/" + UUID.randomUUID();
    }

    private InboundActivity flag(String id, String content, URI... objects) {
        var body = json.writeValueAsString(Map.of(
                "@context", "https://www.w3.org/ns/activitystreams",
                "id", id,
                "type", "Flag",
                "actor", reporterUri,
                "content", content,
                "object", List.of(objects).stream().map(URI::toString).toList()));
        return new InboundActivity(id, reporterUri, "Flag", body);
    }
}
