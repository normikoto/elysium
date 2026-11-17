package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.inbound.Inbox;
import dev.biribiri.elysium.activitypub.signature.CavageRequestSigner;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.signing.ActorSigningKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The limit on what each address can have refused, with a low limit in its own context. Each test uses its own address,
 * because counts last for the whole context.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://refused-deliveries.example",
        "elysium.federation.inbox-limits.refused-per-minute=3",
})
@AutoConfigureMockMvc
class RefusedDeliveriesIntegrationTests {

    private static final String BASE = "https://refused-deliveries.example";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ActorService actors;
    @Autowired
    private ActorSigningKeys signingKeys;
    @Autowired
    private CavageRequestSigner signer;

    private Actor alice;

    @BeforeEach
    void createAlice() {
        alice = actors.createLocalIfAbsent("alice", actor -> {
        }).or(() -> actors.findLocalByUsername("alice")).orElseThrow();
    }

    @Test
    void turnsAnAddressAwayAfterTooManyRefusedRequests() throws Exception {
        var address = "198.18.0.1";
        for (var i = 0; i < 3; i++) {
            mockMvc.perform(from(address, unsigned(activity())))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(from(address, signed(activity())))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", matchesPattern("[1-9]|[1-5][0-9]|60")));
        mockMvc.perform(from("198.18.0.2", signed(activity())))
                .andExpect(status().isAccepted());
    }

    @Test
    void countsBodiesThatAreTooLarge() throws Exception {
        var address = "198.18.0.3";
        for (var i = 0; i < 3; i++) {
            mockMvc.perform(from(address, unsigned("x".repeat(Inbox.MAX_BODY_BYTES + 1))))
                    .andExpect(status().isContentTooLarge());
        }

        mockMvc.perform(from(address, signed(activity()))).andExpect(status().isTooManyRequests());
    }

    @Test
    void doesNotCountDeliveriesThatAreAccepted() throws Exception {
        var address = "198.18.0.4";
        for (var i = 0; i < 5; i++) {
            mockMvc.perform(from(address, signed(activity()))).andExpect(status().isAccepted());
        }
        for (var i = 0; i < 2; i++) {
            mockMvc.perform(from(address, unsigned(activity()))).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(from(address, signed(activity()))).andExpect(status().isAccepted());
    }

    /**
     * The base URL is public, so a request from a private address is probably the proxy's, and stands for every client.
     */
    @Test
    void skipsTheLimitForWhatSeemsToBeAProxy() throws Exception {
        var address = "10.0.0.1";
        for (var i = 0; i < 5; i++) {
            mockMvc.perform(from(address, unsigned(activity()))).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(from(address, signed(activity()))).andExpect(status().isAccepted());
    }

    private String activity() {
        var id = alice.uri() + "/activities/" + UUID.randomUUID();
        return "{\"id\":\"" + id + "\",\"type\":\"Test\",\"actor\":\"" + alice.uri() + "\"}";
    }

    private static MockHttpServletRequestBuilder unsigned(String body) {
        return post("/inbox").contentType("application/activity+json").content(body);
    }

    private MockHttpServletRequestBuilder signed(String body) {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        var headers = signer.sign("POST", URI.create(BASE + "/inbox"), bytes, signingKeys.forActor(alice).orElseThrow());
        return unsigned(body).headers(headers);
    }

    private static MockHttpServletRequestBuilder from(String address, MockHttpServletRequestBuilder request) {
        return request.with(mock -> {
            mock.setRemoteAddr(address);
            return mock;
        });
    }
}
