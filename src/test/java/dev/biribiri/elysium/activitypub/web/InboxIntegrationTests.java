package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.inbound.Inbox;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentStubs;
import dev.biribiri.elysium.activitypub.signature.CavageRequestSigner;
import dev.biribiri.elysium.activitypub.signature.Rfc9421RequestSigner;
import dev.biribiri.elysium.activitypub.signature.VerifiedSignature;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.actor.signing.ActorSigningKeys;
import dev.biribiri.elysium.actor.signing.SigningKey;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sends requests through the real security filters, signed with a real local actor's key. Fetching keys from other servers
 * is tested in {@code PublicKeyResolverTests}, and by the federation lab. The base URL gives this class its own context, so
 * that its limits aren't shared. Each test uses its own address, because they last for the whole context.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.instance.base-url=https://inbox.example")
@AutoConfigureMockMvc
class InboxIntegrationTests {

    private static final String BASE = "https://inbox.example";
    private static final String ACTIVITY_JSON = "application/activity+json";
    private static final AtomicInteger nextAddress = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ActorService actors;
    @Autowired
    private ActorSigningKeys signingKeys;
    @Autowired
    private CavageRequestSigner signer;
    @Autowired
    private Rfc9421RequestSigner rfc9421Signer;
    @Autowired
    private Inbox inbox;
    @Autowired
    private DomainBlocks blocks;
    @Autowired
    private JdbcClient jdbc;
    @MockitoSpyBean
    private ActivityDocuments documents;

    @ParameterizedTest
    @ValueSource(strings = {"/users/alice/inbox", "/users/ALICE/inbox", "/inbox", "/actor/inbox"})
    void queuesASignedActivity(String path) throws Exception {
        var alice = alice();
        var id = newId(alice);

        signed(path, alice, activity(id, alice.uri().toString())).andExpect(status().isAccepted())
                .andExpect(content().string(""));

        assertThat(stored(id)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/users/alice/inbox", "/inbox"})
    void queuesAnActivitySignedWithRfc9421(String path) throws Exception {
        var alice = alice();
        var id = newId(alice);
        var body = activity(id, alice.uri().toString());
        var headers = rfc9421Signer.sign(
                "POST", URI.create(BASE + path), bytes(body), signingKeys.forActor(alice).orElseThrow());

        send(post(path).contentType(ACTIVITY_JSON).headers(headers).content(body)).andExpect(status().isAccepted());

        assertThat(stored(id)).isEqualTo(1);
    }

    @Test
    void acceptsAnyLdJsonProfile() throws Exception {
        var alice = alice();
        var id = newId(alice);
        var body = activity(id, alice.uri().toString());

        send(signedRequest("/inbox", alice, body)
                .contentType("application/ld+json; profile=\"https://www.w3.org/ns/activitystreams\""))
                .andExpect(status().isAccepted());

        assertThat(stored(id)).isEqualTo(1);
    }

    @Test
    void refusesAnUnsignedRequestToTheInstanceActorsInbox() throws Exception {
        send(post("/actor/inbox").contentType(ACTIVITY_JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void doesNotCreateASession() throws Exception {
        var alice = alice();

        var result = signed("/inbox", alice, activity(newId(alice), alice.uri().toString()))
                .andExpect(status().isAccepted())
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
    }

    @Test
    void answersARepeatTheSameWay() throws Exception {
        var alice = alice();
        var id = newId(alice);
        var body = activity(id, alice.uri().toString());

        signed("/inbox", alice, body).andExpect(status().isAccepted());
        signed("/users/alice/inbox", alice, body).andExpect(status().isAccepted());

        assertThat(stored(id)).isEqualTo(1);
    }

    @Test
    void refusesAnUnsignedRequest() throws Exception {
        var alice = alice();
        var id = newId(alice);

        send(post("/inbox").contentType(ACTIVITY_JSON).content(activity(id, alice.uri().toString())))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));

        assertThat(stored(id)).isZero();
    }

    @Test
    void refusesABodyThatDoesNotMatchTheSignature() throws Exception {
        var alice = alice();
        var id = newId(alice);

        send(signedRequest("/inbox", alice, activity(newId(alice), alice.uri().toString()))
                .content(activity(id, alice.uri().toString())))
                .andExpect(status().isUnauthorized());

        assertThat(stored(id)).isZero();
    }

    @Test
    void refusesASignatureMadeForAnotherInbox() throws Exception {
        var alice = alice();
        var body = activity(newId(alice), alice.uri().toString());
        var signedForShared = signer.sign("POST", URI.create(BASE + "/inbox"), bytes(body), signingKeys.forActor(alice).orElseThrow());

        send(post("/users/alice/inbox").contentType(ACTIVITY_JSON).headers(signedForShared).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void doesNotFindAnInboxForAnUnknownUser() throws Exception {
        var alice = alice();

        signed("/users/nobody/inbox", alice, activity(newId(alice), alice.uri().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void refusesOtherContentTypes() throws Exception {
        send(post("/inbox").contentType("text/plain").content("{}")).andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void refusesABodyOverTheLimit() throws Exception {
        var alice = alice();

        signed("/inbox", alice, "x".repeat(1024 * 1024 + 1)).andExpect(status().isContentTooLarge());
    }

    @Test
    void acceptsABodyAtTheLimit() throws Exception {
        var alice = alice();
        var id = newId(alice);
        var start = "{\"id\":\"" + id + "\",\"type\":\"Test\",\"actor\":\"" + alice.uri() + "\",\"x\":\"";
        var body = start + "x".repeat(1024 * 1024 - start.length() - 2) + "\"}";

        signed("/inbox", alice, body).andExpect(status().isAccepted());

        assertThat(stored(id)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{\"id\":\"https://inbox.example/has space\",\"type\":\"Test\",\"actor\":\"https://inbox.example/users/alice\"}"})
    void refusesASignedBodyThatIsNotAnActivity(String body) throws Exception {
        signed("/inbox", alice(), body).andExpect(status().isBadRequest());
    }

    @Test
    void refusesAnIdTooLongToStore() throws Exception {
        var alice = alice();

        signed("/inbox", alice, activity(BASE + "/" + "x".repeat(2048), alice.uri().toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesAKeyOnABlockedServer() throws Exception {
        blocks.create("blocked-key.example", null);

        var keyId = "https://sub.blocked-key.example/users/bob#main-key";
        var address = newAddress();
        // More than the limit on key fetches, which this has to leave alone because nothing is fetched.
        for (var i = 0; i < 35; i++) {
            send(signedWithKeyId(keyId, alice()), address).andExpect(status().isForbidden());
        }
    }

    @Test
    void refusesAnActivityFromABlockedServerThatGetsPastTheSignatureCheck() {
        blocks.create("blocked-actor.example", null);
        var actor = "https://blocked-actor.example/users/bob";
        var id = "https://blocked-actor.example/activities/" + UUID.randomUUID();

        var outcome = inbox.receive(new VerifiedSignature(actor + "#main-key", URI.create(actor)), bytes(activity(id, actor)));

        assertThat(outcome.outcome()).isEqualTo(Inbox.Outcome.FORBIDDEN);
        assertThat(stored(id)).isZero();
    }

    @Test
    void refusesAnActivityFromASuspendedActorThatGetsPastTheSignatureCheck() {
        var domain = "suspended-" + UUID.randomUUID() + ".example";
        var actor = URI.create("https://" + domain + "/users/bob");
        var stored = actors.upsertRemote(new RemoteActorProfile(actor, "bob", domain, URI.create(actor + "/inbox"), null, null, null));
        actors.suspend(stored.id());
        var id = "https://" + domain + "/activities/" + UUID.randomUUID();

        var outcome = inbox.receive(new VerifiedSignature(actor + "#main-key", actor), bytes(activity(id, actor.toString())));

        assertThat(outcome.outcome()).isEqualTo(Inbox.Outcome.FORBIDDEN);
        assertThat(stored(id)).isZero();
    }

    @Test
    void refusesAKeyOfASuspendedActorWithoutFetchingIt() throws Exception {
        var domain = "suspended-key-" + UUID.randomUUID() + ".example";
        var actor = URI.create("https://" + domain + "/users/bob");
        var stored = actors.upsertRemote(new RemoteActorProfile(actor, "bob", domain, URI.create(actor + "/inbox"), null, null, null));
        actors.suspend(stored.id());

        send(signedWithKeyId(actor + "#main-key", alice()), newAddress()).andExpect(status().isForbidden());
    }

    /**
     * Otherwise this activity would be stored under the other server's ID, and that server's real one would look like a
     * repeat of it.
     */
    @Test
    void doesNotLetOneServerUseAnotherServersId() throws Exception {
        var alice = alice();
        var id = "https://other.example/activities/" + UUID.randomUUID();
        var realActor = "https://other.example/users/bob";

        signed("/inbox", alice, activity(id, alice.uri().toString())).andExpect(status().isForbidden());
        assertThat(stored(id)).isZero();

        var outcome = inbox.receive(
                new VerifiedSignature(realActor + "#main-key", URI.create(realActor)), bytes(activity(id, realActor)));

        assertThat(outcome.outcome()).isEqualTo(Inbox.Outcome.ACCEPTED);
        assertThat(storedActor(id)).isEqualTo(realActor);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://inbox.example/1",
            "https://inbox.example:8443/1",
            "https://user@inbox.example/1",
            "/1",
            "urn:uuid:1"
    })
    void refusesAnIdThatIsNotOnItsActorsServer(String id) throws Exception {
        var alice = alice();

        signed("/inbox", alice, activity(id, alice.uri().toString())).andExpect(status().isForbidden());

        assertThat(stored(id)).isZero();
    }

    @Test
    void comparesServersRegardlessOfCase() throws Exception {
        var alice = alice();
        var id = "https://INBOX.example:443/activities/" + UUID.randomUUID();

        signed("/inbox", alice, activity(id, alice.uri().toString())).andExpect(status().isAccepted());

        assertThat(stored(id)).isEqualTo(1);
    }

    /**
     * The addresses that these keys name are refused without connecting to anything, but they do count as fetches. Each has
     * its own host, so that this only reaches the limit for the sender.
     */
    @Test
    void limitsTheKeyFetchesThatAnAddressCanCause() throws Exception {
        var alice = alice();
        var address = newAddress();
        for (var i = 1; i <= 30; i++) {
            send(signedWithKeyId("https://127.0.0." + i + "/users/u#main-key", alice), address)
                    .andExpect(status().isUnauthorized());
        }

        send(signedWithKeyId("https://127.0.0.31/users/u#main-key", alice), address)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", matchesPattern("[1-9][0-9]*")));

        send(signedWithKeyId("https://127.0.0.31/users/u#main-key", alice), newAddress())
                .andExpect(status().isUnauthorized());
    }

    /**
     * Only the first of these makes a fetch, because the failure is remembered.
     */
    @Test
    void doesNotCountRepeatsOfAKeyThatWasAlreadyLookedUp() throws Exception {
        var alice = alice();
        var address = newAddress();
        for (var i = 0; i < 31; i++) {
            send(signedWithKeyId("https://127.0.1.1/users/u#main-key", alice), address)
                    .andExpect(status().isUnauthorized());
        }
    }

    /**
     * None of these need a key to be fetched, so a server whose keys are cached can't be limited by any of them.
     */
    @Test
    void doesNotLimitRequestsThatFetchNothing() throws Exception {
        var alice = alice();
        var address = newAddress();
        for (var i = 0; i < 31; i++) {
            send(signedRequest("/inbox", alice, activity(newId(alice), alice.uri().toString())), address)
                    .andExpect(status().isAccepted());
            send(signedRequest("/inbox", alice, "not json"), address).andExpect(status().isBadRequest());
            send(post("/inbox").contentType(ACTIVITY_JSON).content("{}"), address).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void acceptsAnActorSentAsAnArrayOfOne() throws Exception {
        var alice = alice();
        var id = newId(alice);
        var body = "{\"id\":\"" + id + "\",\"type\":\"Test\",\"actor\":[\"" + alice.uri() + "\"]}";

        signed("/inbox", alice, body).andExpect(status().isAccepted());

        assertThat(stored(id)).isEqualTo(1);
    }

    @Test
    void dropsAnActivityWithSeveralActors() throws Exception {
        var alice = alice();
        var id = newId(alice);
        var body = "{\"id\":\"" + id + "\",\"type\":\"Test\",\"actor\":[\"" + alice.uri() + "\",\"" + BASE + "/users/bob\"]}";

        signed("/inbox", alice, body).andExpect(status().isAccepted());

        assertThat(stored(id)).isZero();
    }

    @Test
    void stillDeniesReadingAnInbox() throws Exception {
        mockMvc.perform(get("/users/alice/inbox")).andExpect(status().isForbidden());
        mockMvc.perform(get("/inbox")).andExpect(status().isForbidden());
        mockMvc.perform(get("/actor/inbox")).andExpect(status().isForbidden());
    }

    @Test
    void acceptsAStoredActorsOwnDeleteWhenItsKeyIsGone() throws Exception {
        var deleted = goneRemote();
        var body = "{\"id\":\"%s#delete\",\"type\":\"Delete\",\"actor\":\"%s\",\"object\":\"%s\"}"
                .formatted(deleted, deleted, deleted);

        send(signedAs(deleted + "#main-key", body)).andExpect(status().isAccepted());

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM scheduled_tasks
                        WHERE task_name = 'deletion-check' AND convert_from(task_data, 'UTF8') LIKE :actor
                        """)
                .param("actor", "%" + deleted + "%")
                .query(Long.class)
                .single()).isOne();
    }

    @Test
    void refusesAnythingElseWhenItsKeyIsGone() throws Exception {
        var deleted = goneRemote();

        send(signedAs(deleted + "#main-key", activity(deleted + "/activities/1", deleted.toString())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsASuspendedActorsOwnDeleteForChecking() throws Exception {
        var suspended = goneRemote();
        actors.suspend(actors.findByUri(suspended).orElseThrow().id());
        var body = "{\"id\":\"%s#delete\",\"type\":\"Delete\",\"actor\":\"%s\",\"object\":\"%s\"}"
                .formatted(suspended, suspended, suspended);

        send(signedAs(suspended + "#main-key", body)).andExpect(status().isAccepted());
        send(signedAs(suspended + "#main-key", activity(suspended + "/activities/1", suspended.toString())))
                .andExpect(status().isForbidden());
    }

    /**
     * @return a stored remote actor whose server now answers that its key is gone
     */
    private URI goneRemote() throws Exception {
        var domain = "g" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + domain + "/users/gone");
        actors.upsertRemote(new RemoteActorProfile(uri, "gone", domain, URI.create(uri + "/inbox"), null, null, null));
        DocumentStubs.gone(documents, uri);
        return uri;
    }

    private MockHttpServletRequestBuilder signedAs(String keyId, String body) {
        var key = new SigningKey(keyId, signingKeys.forActor(alice()).orElseThrow().privateKey());
        var headers = signer.sign("POST", URI.create(BASE + "/inbox"), bytes(body), key);
        return post("/inbox").contentType(ACTIVITY_JSON).headers(headers).content(body);
    }

    private Actor alice() {
        return actors.createLocalIfAbsent("alice", actor -> {
        }).or(() -> actors.findLocalByUsername("alice")).orElseThrow();
    }

    private static String newId(Actor actor) {
        return actor.uri() + "/activities/" + UUID.randomUUID();
    }

    private static String activity(String id, String actor) {
        return "{\"id\":\"" + id + "\",\"type\":\"Test\",\"actor\":\"" + actor + "\"}";
    }

    private ResultActions signed(String path, Actor actor, String body) throws Exception {
        return send(signedRequest(path, actor, body));
    }

    private MockHttpServletRequestBuilder signedRequest(String path, Actor actor, String body) {
        var headers = signer.sign("POST", URI.create(BASE + path), bytes(body), signingKeys.forActor(actor).orElseThrow());
        return post(path).contentType(ACTIVITY_JSON).headers(headers).content(body);
    }

    private MockHttpServletRequestBuilder signedWithKeyId(String keyId, Actor actor) {
        var body = activity(newId(actor), actor.uri().toString());
        var key = new SigningKey(keyId, signingKeys.forActor(actor).orElseThrow().privateKey());
        var headers = signer.sign("POST", URI.create(BASE + "/inbox"), bytes(body), key);
        return post("/inbox").contentType(ACTIVITY_JSON).headers(headers).content(body);
    }

    private ResultActions send(MockHttpServletRequestBuilder request) throws Exception {
        return send(request, newAddress());
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String address) throws Exception {
        return mockMvc.perform(request.with(sent -> {
            sent.setRemoteAddr(address);
            return sent;
        }));
    }

    private static String newAddress() {
        var next = nextAddress.incrementAndGet();
        return "203.0." + (next / 256) + "." + (next % 256);
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

    private String storedActor(String id) {
        return jdbc.sql("SELECT actor_uri FROM inbound_activity WHERE activity_id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }
}
