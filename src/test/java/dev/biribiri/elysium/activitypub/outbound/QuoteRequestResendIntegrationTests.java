package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.QuoteApproval;
import dev.biribiri.elysium.content.QuoteService;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * No job worker runs, so the resend is run by hand, and deliveries are only queued.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://resends.example",
        "elysium.jobs.worker-enabled=false"
})
class QuoteRequestResendIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final AtomicInteger next = new AtomicInteger();

    @Autowired
    private QuoteDeliveries quoteDeliveries;
    @Autowired
    private StatusService statuses;
    @Autowired
    private QuoteService quotes;
    @Autowired
    private ActorService actors;
    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private RemoteActorResolver resolver;

    private Actor author;
    private Actor remote;
    private UUID quote;
    private URI request;

    @BeforeEach
    void quote() {
        var username = "resender" + next.incrementAndGet();
        actors.createLocalIfAbsent(username, actor -> {
        });
        author = actors.findLocalByUsername(username).orElseThrow();
        var domain = "r" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + domain + "/users/dave");
        remote = actors.upsertRemote(new RemoteActorProfile(uri, "dave", domain, URI.create(uri + "/inbox"), null,
                URI.create("https://" + domain + "/inbox"), null, URI.create(uri + "/followers")));
        var quoted = statuses.storeRemote(new RemoteStatus(URI.create(uri + "/statuses/1"), null, remote.id(),
                "<p>remote</p>", null, null, Visibility.PUBLIC, false, null, Instant.now(), null, Map.of(), List.of(),
                List.of(), null, new QuoteApproval(Set.of(QuoteApproval.Audience.PUBLIC), Set.of()))).orElseThrow();
        var status = statuses.create(author.id(),
                new NewStatus("quoting", null, null, Visibility.PUBLIC, false, null, List.of(), quoted, null));
        quote = status.id();
        request = URI.create(status.uri() + "#quote_request");
        await().atMost(WAIT).until(() -> deliveries() == 1);
    }

    @Test
    void anUnansweredRequestIsSentAgainAfterAWhile() {
        var resend = resendScheduledAt();
        assertThat(resend).isBetween(Instant.now().plus(QuoteDeliveries.RESEND_AFTER).minusSeconds(30),
                Instant.now().plus(QuoteDeliveries.RESEND_AFTER));
        delivered();

        quoteDeliveries.resend(job());

        assertThat(deliveries()).isEqualTo(1);
    }

    @Test
    void aRequestThatsStillBeingTriedIsntQueuedTwice() {
        quoteDeliveries.resend(job());

        assertThat(deliveries()).isEqualTo(1);
    }

    @Test
    void anAnsweredRequestIsntSentAgain() {
        delivered();
        assertThat(quotes.answerRequest(request, remote.uri(), URI.create(remote.uri() + "/quote_authorizations/1")))
                .isTrue();

        quoteDeliveries.resend(job());

        assertThat(deliveries()).isZero();
    }

    @Test
    void aDeletedQuoteIsntSentAgain() {
        delivered();
        statuses.delete(quote, author.id());

        quoteDeliveries.resend(job());

        assertThat(deliveries()).isZero();
    }

    private QuoteRequestResendJob job() {
        return new QuoteRequestResendJob(quote, author.id(), Instant.now());
    }

    private Instant resendScheduledAt() {
        return jdbc.sql("""
                        SELECT execution_time FROM scheduled_tasks
                        WHERE task_name = 'quote-request-resend'
                          AND convert_from(task_data, 'UTF8')::json ->> 'statusId' = :id
                        """)
                .param("id", quote.toString())
                .query(Instant.class)
                .single();
    }

    /**
     * As if the first delivery had finished.
     */
    private void delivered() {
        jdbc.sql(deliveriesQuery("DELETE")).param("id", request.toString()).update();
    }

    private int deliveries() {
        return jdbc.sql(deliveriesQuery("SELECT count(*)")).param("id", request.toString()).query(Integer.class).single();
    }

    private static String deliveriesQuery(String action) {
        return action + """
                 FROM scheduled_tasks
                WHERE task_name = 'activity-delivery'
                  AND convert_from(task_data, 'UTF8')::json ->> 'activityId' = :id
                """;
    }
}
