package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.CavageRequestSigner;
import dev.biribiri.elysium.activitypub.signature.DocumentStubs;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.actor.signing.SigningKey;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fetches of statuses signed by actors on other servers, whose keys are served by a spy rather than fetched. The base URL
 * gives this class its own context, so that its fetch limits aren't shared, and each request comes from its own address.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.instance.base-url=https://notes.example")
@AutoConfigureMockMvc
class NoteFetchIntegrationTests {

    private static final String BASE = "https://notes.example";
    private static final String ACTIVITY_JSON = "application/activity+json";
    private static final AtomicInteger nextAddress = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ActorService actors;
    @Autowired
    private StatusService statuses;
    @Autowired
    private FollowService follows;
    @Autowired
    private DomainBlocks blocks;
    @Autowired
    private CavageRequestSigner signer;
    @Autowired
    private TransactionTemplate transaction;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;
    @MockitoSpyBean
    private ActivityDocuments documents;

    @Test
    void servesAFollowersOnlyStatusToThoseFollowingWhenItWasPosted() throws Exception {
        var early = remote("early");
        var late = remote("late");
        followAlice(early, Duration.ofHours(-1));
        var status = post("only followers", Visibility.PRIVATE);
        followAlice(late, Duration.ofHours(1));

        fetch(status, early)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(status.uri().toString()))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, private"))
                .andExpect(header().stringValues(HttpHeaders.VARY, hasItem(containsString("Signature"))))
                .andExpect(header().stringValues(HttpHeaders.VARY, hasItem(containsString("Accept"))));
        fetch(status, late).andExpect(status().isNotFound());
        send(unsigned(status)).andExpect(status().isNotFound());
    }

    @Test
    void servesAFollowersOnlyStatusToSomeoneWhoWasSentItAndFollowedAgainButNotToAFormerFollower() throws Exception {
        var again = remote("again");
        var former = remote("former");
        var late = remote("late");
        followAlice(again, Duration.ofHours(-1));
        followAlice(former, Duration.ofHours(-1));
        var status = post("only followers", Visibility.PRIVATE);
        for (var recipient : List.of(again, former)) {
            jdbc.sql("""
                            INSERT INTO followers_only_recipient (status_id, author_id, actor_id)
                            VALUES (:status, :author, :actor)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("status", status.id())
                    .param("author", alice().id())
                    .param("actor", recipient.actor().id())
                    .update();
        }
        // Following again makes the follow newer than the status.
        followAlice(again, Duration.ofHours(1));
        follows.removeForActor(former.actor().id());
        followAlice(late, Duration.ofHours(1));

        fetch(status, again).andExpect(status().isOk());
        fetch(status, former).andExpect(status().isNotFound());
        fetch(status, late).andExpect(status().isNotFound());
    }

    @Test
    void servesAFollowersOnlyOrDirectStatusToTheActorsItMentions() throws Exception {
        var mentioned = remote("mentioned");
        var other = remote("other");
        followAlice(other, Duration.ofHours(-1));
        var direct = post("@mentioned@" + mentioned.actor().domain() + " secret", Visibility.DIRECT);
        var followersOnly = post("@mentioned@" + mentioned.actor().domain() + " hello", Visibility.PRIVATE);

        fetch(direct, mentioned).andExpect(status().isOk());
        fetch(followersOnly, mentioned).andExpect(status().isOk());
        // A follower isn't sent a direct status, so it can't fetch one.
        fetch(direct, other).andExpect(status().isNotFound());
    }

    @Test
    void doesNotCountAnActorForOthersOnItsServer() throws Exception {
        var follower = remote("follower");
        var instanceActor = remoteOn(follower.actor().domain(), "actor");
        followAlice(follower, Duration.ofHours(-1));
        var status = post("only followers", Visibility.PRIVATE);

        fetch(status, instanceActor).andExpect(status().isNotFound());
    }

    @Test
    void answersASignatureThatCannotBeAcceptedAsUnsigned() throws Exception {
        var follower = remote("follower");
        followAlice(follower, Duration.ofHours(-1));
        var hidden = post("only followers", Visibility.PRIVATE);
        var shown = post("everyone", Visibility.PUBLIC);

        var forAnotherPath = signer.sign("GET", URI.create(BASE + path(shown)), new byte[0], follower.key());
        send(get(path(hidden)).accept(ACTIVITY_JSON).headers(forAnotherPath)).andExpect(status().isNotFound());
        send(get(path(shown)).accept(ACTIVITY_JSON).header("Signature", "not a signature"))
                .andExpect(status().isOk());
        var wrongKey = new Signer(follower.actor(), remote("stranger").key().privateKey());
        fetch(hidden, wrongKey).andExpect(status().isNotFound());
        fetch(shown, wrongKey).andExpect(status().isOk());
    }

    @Test
    void answersASuspendedOrBlockedSignerAsUnsigned() throws Exception {
        var suspended = remote("suspended");
        var blocked = remote("blocked");
        followAlice(suspended, Duration.ofHours(-1));
        followAlice(blocked, Duration.ofHours(-1));
        var status = post("only followers", Visibility.PRIVATE);
        actors.suspend(suspended.actor().id());
        blocks.create(blocked.actor().domain(), "test");

        fetch(status, suspended).andExpect(status().isNotFound());
        fetch(status, blocked).andExpect(status().isNotFound());
    }

    @Test
    void keepsADeletedFollowersOnlyStatusHidden() throws Exception {
        var mentioned = remote("mentioned");
        followAlice(mentioned, Duration.ofHours(-1));
        var status = post("@mentioned@" + mentioned.actor().domain() + " hello", Visibility.PRIVATE);
        statuses.delete(status.id(), alice().id());

        fetch(status, mentioned).andExpect(status().isNotFound());
    }

    @Test
    void doesNotMarkAPublicStatusPrivate() throws Exception {
        var status = post("everyone", Visibility.PUBLIC);

        fetch(status, remote("anyone"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, not(containsString("private"))));
    }

    @Test
    void refusesASignatureFromAnotherOrigin() throws Exception {
        var status = post("everyone", Visibility.PUBLIC);

        send(options(path(status))
                .header(HttpHeaders.ORIGIN, "https://elsewhere.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "signature"))
                .andExpect(status().isForbidden());
        send(unsigned(status).header(HttpHeaders.ORIGIN, "https://elsewhere.example"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, not(containsString("private"))));
    }

    /**
     * An actor on another server, with a key that it signs with.
     */
    private record Signer(Actor actor, PrivateKey privateKey) {

        SigningKey key() {
            return new SigningKey(actor.uri() + "#main-key", privateKey);
        }
    }

    private Signer remote(String username) throws Exception {
        return remoteOn("n" + UUID.randomUUID() + ".test", username);
    }

    private Signer remoteOn(String domain, String username) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        var uri = URI.create("https://" + domain + "/users/" + username);
        var actor = actors.upsertRemote(new RemoteActorProfile(
                uri, username, domain, URI.create(uri + "/inbox"), null, null, pem));
        var document = json.createObjectNode()
                .put("id", uri.toString())
                .put("type", "Person")
                .put("inbox", uri + "/inbox")
                .set("publicKey", json.createObjectNode()
                        .put("id", uri + "#main-key")
                        .put("owner", uri.toString())
                        .put("publicKeyPem", pem));
        DocumentStubs.actor(documents, uri, document);
        return new Signer(actor, pair.getPrivate());
    }

    /**
     * @param offset when the follow started, from now
     */
    private void followAlice(Signer follower, Duration offset) {
        var alice = alice();
        var activity = follower.actor().uri() + "/follows/" + UUID.randomUUID();
        var now = Instant.now();
        transaction.executeWithoutResult(tx -> follows.receive(follower.actor().id(), follower.actor().uri(), alice.id(),
                activity, new FollowService.Arrival(now, now)));
        jdbc.sql("UPDATE follow SET created_at = :at WHERE follower_id = :follower AND followed_id = :followed")
                .param("at", Timestamp.from(now.plus(offset)))
                .param("follower", follower.actor().id())
                .param("followed", alice.id())
                .update();
    }

    private Status post(String text, Visibility visibility) {
        return statuses.create(alice().id(), new NewStatus(text, null, null, visibility, false, null));
    }

    private Actor alice() {
        return actors.createLocalIfAbsent("alice", actor -> {
        }).or(() -> actors.findLocalByUsername("alice")).orElseThrow();
    }

    private static String path(Status status) {
        return status.uri().getRawPath();
    }

    private ResultActions fetch(Status status, Signer as) throws Exception {
        var headers = signer.sign("GET", URI.create(BASE + path(status)), new byte[0], as.key());
        return send(get(path(status)).accept(ACTIVITY_JSON).headers(headers));
    }

    private static MockHttpServletRequestBuilder unsigned(Status status) {
        return get(path(status)).accept(ACTIVITY_JSON);
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
