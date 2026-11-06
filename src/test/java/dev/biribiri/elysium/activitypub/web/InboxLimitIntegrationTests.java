package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.inbound.Inbox;
import dev.biribiri.elysium.activitypub.signature.CavageRequestSigner;
import dev.biribiri.elysium.activitypub.signature.VerifiedSignature;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.signing.ActorSigningKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The limits on what each server can have stored, with low limits in their own context. Each test uses its own domain,
 * because counts last for the whole context.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://inbox-limits.example",
        "elysium.federation.inbox-limits.activities-per-minute=3",
        "elysium.federation.inbox-limits.data-per-hour=2MB",
        "elysium.federation.inbox-limits.exempt-domains=trusted.example",
})
@AutoConfigureMockMvc
class InboxLimitIntegrationTests {

    private static final String BASE = "https://inbox-limits.example";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ActorService actors;
    @Autowired
    private ActorSigningKeys signingKeys;
    @Autowired
    private CavageRequestSigner signer;
    @Autowired
    private Inbox inbox;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void answersA429WithRetryAfterOnceAServerIsOverItsLimit() throws Exception {
        var alice = actors.createLocalIfAbsent("alice", actor -> {
        }).or(() -> actors.findLocalByUsername("alice")).orElseThrow();

        for (var i = 0; i < 3; i++) {
            send(alice.uri() + "/activities/" + UUID.randomUUID(), alice.uri().toString(), alice)
                    .andExpect(status().isAccepted());
        }
        var id = alice.uri() + "/activities/" + UUID.randomUUID();
        send(id, alice.uri().toString(), alice)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", matchesPattern("[1-9]|[1-5][0-9]|60")));

        assertThat(stored(id)).isZero();
    }

    @Test
    void countsSubdomainsAsOneServer() {
        var domain = newDomain();
        for (var i = 0; i < 3; i++) {
            assertThat(receive("a" + i + "." + domain)).isEqualTo(Inbox.Outcome.ACCEPTED);
        }

        assertThat(receive("other." + domain)).isEqualTo(Inbox.Outcome.RATE_LIMITED);
        assertThat(receive(newDomain())).isEqualTo(Inbox.Outcome.ACCEPTED);
    }

    @Test
    void limitsTheDataAServerSends() {
        var host = newDomain();
        var padding = "x".repeat(900 * 1024);

        assertThat(receive(host, padding)).isEqualTo(Inbox.Outcome.ACCEPTED);
        assertThat(receive(host, padding)).isEqualTo(Inbox.Outcome.ACCEPTED);
        assertThat(receive(host, padding)).isEqualTo(Inbox.Outcome.RATE_LIMITED);
        assertThat(receive(host, "")).isEqualTo(Inbox.Outcome.ACCEPTED);
    }

    @Test
    void doesNotLimitAnExemptDomain() {
        for (var i = 0; i < 5; i++) {
            assertThat(receive("social.trusted.example")).isEqualTo(Inbox.Outcome.ACCEPTED);
        }
    }

    @Test
    void doesNotCountActivitiesThatAreRefused() {
        var host = newDomain();
        var actor = "https://" + host + "/users/bob";
        var signature = new VerifiedSignature(actor + "#main-key", URI.create(actor));
        for (var i = 0; i < 5; i++) {
            var elsewhere = "https://other.example/activities/" + UUID.randomUUID();
            assertThat(inbox.receive(signature, bytes(activity(elsewhere, actor, ""))).outcome())
                    .isEqualTo(Inbox.Outcome.FORBIDDEN);
        }

        assertThat(receive(host)).isEqualTo(Inbox.Outcome.ACCEPTED);
    }

    private Inbox.Outcome receive(String host) {
        return receive(host, "");
    }

    private Inbox.Outcome receive(String host, String padding) {
        var actor = "https://" + host + "/users/bob";
        var id = "https://" + host + "/activities/" + UUID.randomUUID();
        return inbox.receive(new VerifiedSignature(actor + "#main-key", URI.create(actor)), bytes(activity(id, actor, padding)))
                .outcome();
    }

    private ResultActions send(String id, String actor, Actor signedBy)
            throws Exception {
        var body = activity(id, actor, "");
        var headers = signer.sign("POST", URI.create(BASE + "/inbox"), bytes(body), signingKeys.forActor(signedBy).orElseThrow());
        return mockMvc.perform(post("/inbox").contentType("application/activity+json").headers(headers).content(body));
    }

    private static String newDomain() {
        return "limits-" + UUID.randomUUID() + ".example";
    }

    private static String activity(String id, String actor, String padding) {
        return "{\"id\":\"" + id + "\",\"type\":\"Test\",\"actor\":\"" + actor + "\",\"padding\":\"" + padding + "\"}";
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private long stored(String id) {
        return jdbc.sql("SELECT count(*) FROM inbound_activity WHERE activity_id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }
}
