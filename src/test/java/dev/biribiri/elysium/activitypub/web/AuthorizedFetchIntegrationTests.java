package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.CavageRequestSigner;
import dev.biribiri.elysium.activitypub.web.RemoteSigners.Signer;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fetches of documents when the server requires signed fetches. The base URL gives this class its own context, so that the
 * setting and its fetch limits aren't shared, and each request comes from its own address unless a test says otherwise.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://authorized.example",
        "elysium.federation.authorized-fetch=true"})
@AutoConfigureMockMvc
class AuthorizedFetchIntegrationTests {

    private static final String BASE = "https://authorized.example";
    private static final String ACTIVITY_JSON = "application/activity+json";
    private static final AtomicInteger nextAddress = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ActorService actors;
    @Autowired
    private StatusService statuses;
    @Autowired
    private DomainBlocks blocks;
    @Autowired
    private CavageRequestSigner signer;
    @Autowired
    private JsonMapper json;
    @MockitoSpyBean
    private ActivityDocuments documents;

    @Test
    void refusesUnsignedFetchesOfUsersDocuments() throws Exception {
        var alice = local("alice");
        var status = post(alice, Visibility.PUBLIC);
        var deleted = post(alice, Visibility.PUBLIC);
        statuses.delete(deleted.id(), alice.id());
        var paths = List.of(
                "/users/alice/followers", "/users/alice/followers?page=true", "/users/alice/following",
                "/users/alice/outbox", "/users/alice/outbox?page=true", "/users/alice/collections/featured",
                path(status), path(status) + "/likes", path(status) + "/shares", path(deleted),
                "/users/alice/quote_authorizations/" + UUID.randomUUID());
        var someone = remote("someone");
        for (var path : paths) {
            send(get(path).accept(ACTIVITY_JSON))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().stringValues(HttpHeaders.VARY, hasItem(containsString("Signature"))));
            var signed = path.equals(path(deleted)) ? 410 : path.contains("quote_authorizations") ? 404 : 200;
            fetch(path, someone).andExpect(status().is(signed));
        }
    }

    @Test
    void servesAMinimalActorDocumentToAnUnsignedFetch() throws Exception {
        local("bob");

        send(get("/users/bob").accept(ACTIVITY_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(BASE + "/users/bob"))
                .andExpect(jsonPath("$.preferredUsername").value("bob"))
                .andExpect(jsonPath("$.inbox").exists())
                .andExpect(jsonPath("$.endpoints.sharedInbox").value(BASE + "/inbox"))
                .andExpect(jsonPath("$.publicKey.id").value(BASE + "/users/bob#main-key"))
                .andExpect(jsonPath("$.publicKey.publicKeyPem").exists())
                .andExpect(jsonPath("$.url").doesNotExist())
                .andExpect(jsonPath("$.featured").doesNotExist())
                .andExpect(jsonPath("$.manuallyApprovesFollowers").doesNotExist())
                .andExpect(jsonPath("$.published").doesNotExist())
                .andExpect(header().stringValues(HttpHeaders.VARY, hasItem(containsString("Signature"))));
        fetch("/users/bob", remote("someone"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(BASE + "/users/bob"))
                .andExpect(jsonPath("$.manuallyApprovesFollowers").value(false))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, private"));
    }

    @Test
    void servesSuspendedAndDeletedUsersToUnsignedFetches() throws Exception {
        var suspended = local("suspended");
        var deleted = local("deleted");
        actors.suspend(suspended.id());
        actors.markDeleted(deleted.id());

        send(get("/users/suspended").accept(ACTIVITY_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspended").value(true))
                .andExpect(jsonPath("$.publicKey.publicKeyPem").exists());
        send(get("/users/deleted").accept(ACTIVITY_JSON))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("Tombstone"));
    }

    @Test
    void refusesASignatureThatCannotBeAccepted() throws Exception {
        local("carol");
        var someone = remote("someone");
        var wrongKey = new Signer(someone.uri(), someone.domain(), someone.actor(), remote("stranger").privateKey());

        for (var path : List.of("/users/carol", "/users/carol/followers")) {
            fetch(path, wrongKey).andExpect(status().isUnauthorized());
            send(get(path).accept(ACTIVITY_JSON).header("Signature", "not a signature"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void refusesASuspendedOrBlockedSigner() throws Exception {
        local("dave");
        var suspended = remote("suspended");
        var blocked = remote("blocked");
        actors.suspend(suspended.stored().id());
        blocks.create(blocked.domain(), "test");

        for (var path : List.of("/users/dave", "/users/dave/followers")) {
            fetch(path, suspended).andExpect(status().isForbidden());
            fetch(path, blocked).andExpect(status().isForbidden());
        }
    }

    @Test
    void acceptsASignerThatIsNotStored() throws Exception {
        local("erin");

        fetch("/users/erin/followers", signers().unstored("actor")).andExpect(status().isOk());
    }

    @Test
    void limitsTheKeyFetchesAnAddressCauses() throws Exception {
        local("frank");
        var address = "203.0.113.7";
        for (var i = 0; i < AddressFetchBudgets.MAX_STARTED_PER_MINUTE; i++) {
            fetchFrom(address, "/users/frank/followers", signers().unstored("a" + i)).andExpect(status().isOk());
        }

        fetchFrom(address, "/users/frank/followers", signers().unstored("over"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void leavesDiscoveryTheInstanceActorAndWebPagesUnsigned() throws Exception {
        var grace = local("grace");
        var status = post(grace, Visibility.PUBLIC);

        send(get("/actor").accept(ACTIVITY_JSON)).andExpect(status().isOk());
        send(get("/actor/outbox").accept(ACTIVITY_JSON)).andExpect(status().isOk());
        send(get("/emojis/" + UUID.randomUUID()).accept(ACTIVITY_JSON)).andExpect(status().isNotFound());
        send(get("/.well-known/webfinger").param("resource", "acct:grace@authorized.example"))
                .andExpect(status().isOk());
        send(get("/nodeinfo/2.1")).andExpect(status().isOk());
        send(get("/users/grace").accept(MediaType.TEXT_HTML)).andExpect(status().isOk());
        send(get(path(status)).accept(MediaType.TEXT_HTML)).andExpect(status().isOk());
    }

    @Test
    void onlyLetsOtherSitesReadTheUnsignedAnswer() throws Exception {
        local("heidi");

        for (var path : List.of("/users/heidi", "/users/heidi/followers", "/users/heidi/outbox")) {
            send(options(path)
                    .header(HttpHeaders.ORIGIN, "https://elsewhere.example")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "signature"))
                    .andExpect(status().isForbidden());
        }
        send(get("/users/heidi/followers").accept(ACTIVITY_JSON).header(HttpHeaders.ORIGIN, "https://elsewhere.example"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*"));
    }

    private Signer remote(String username) throws Exception {
        return signers().remote(username);
    }

    private RemoteSigners signers() {
        return new RemoteSigners(actors, documents, json);
    }

    private Actor local(String username) {
        return actors.createLocalIfAbsent(username, actor -> {
        }).or(() -> actors.findAnyLocalByUsername(username)).orElseThrow();
    }

    private Status post(Actor author, Visibility visibility) {
        return statuses.create(author.id(), new NewStatus("hello", null, null, visibility, false, null));
    }

    private static String path(Status status) {
        return status.uri().getRawPath();
    }

    private ResultActions fetch(String path, Signer as) throws Exception {
        return send(signed(path, as));
    }

    private ResultActions fetchFrom(String address, String path, Signer as) throws Exception {
        return mockMvc.perform(signed(path, as).with(sent -> {
            sent.setRemoteAddr(address);
            return sent;
        }));
    }

    private MockHttpServletRequestBuilder signed(String path, Signer as) {
        var headers = signer.sign("GET", URI.create(BASE + path), new byte[0], as.key());
        return get(path).accept(ACTIVITY_JSON).headers(headers);
    }

    private ResultActions send(MockHttpServletRequestBuilder request) throws Exception {
        var next = nextAddress.incrementAndGet();
        var address = "198.51." + (next / 256) + "." + (next % 256);
        return mockMvc.perform(request.with(sent -> {
            sent.setRemoteAddr(address);
            return sent;
        }));
    }
}
