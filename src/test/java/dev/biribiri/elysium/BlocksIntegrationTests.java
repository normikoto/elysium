package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Blocks, mutes, and the user's server blocks, through the client API. No job worker runs, so activities are only queued.
 * Blocks from other servers are tested in {@code BlockIntegrationTests}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://blocks.example",
        "elysium.jobs.worker-enabled=false"
})
@AutoConfigureMockMvc
class BlocksIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";
    private static final Duration WAIT = Duration.ofSeconds(30);

    /**
     * Logging in is slow because of Argon2, so each account's token is only created once.
     */
    private static final Map<String, String> TOKENS = new HashMap<>();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;
    @MockitoBean
    private RemoteActorResolver resolver;

    @BeforeEach
    void setUp() {
        TestAccounts.makeSureAnAccountExists(actors);
        jdbc.sql("DELETE FROM follow").update();
        jdbc.sql("DELETE FROM account_block").update();
        jdbc.sql("DELETE FROM account_mute").update();
        jdbc.sql("DELETE FROM account_domain_block").update();
        jdbc.sql("DELETE FROM inbox_notification").update();
    }

    // Blocks

    @Test
    void blockingEndsTheFollowsBothWays() throws Exception {
        var bob = account("bob");
        var alice = account("alice");
        follow("alice", bob);
        follow("bob", alice);

        mockMvc.perform(withToken(post("/api/v1/accounts/" + bob.id() + "/block"), token("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocking").value(true))
                .andExpect(jsonPath("$.blocked_by").value(false))
                .andExpect(jsonPath("$.following").value(false))
                .andExpect(jsonPath("$.followed_by").value(false));

        assertThat(jdbc.sql("SELECT count(*) FROM follow").query(Long.class).single()).isZero();
        mockMvc.perform(withToken(get("/api/v1/accounts/relationships").param("id[]", alice.id().toString()), token("bob")))
                .andExpect(jsonPath("$[0].blocking").value(false))
                .andExpect(jsonPath("$[0].blocked_by").value(true));
    }

    @Test
    void neitherCanFollowTheOtherWhileBlocked() throws Exception {
        var bob = account("bob");
        var alice = account("alice");
        block("alice", bob);

        mockMvc.perform(withToken(post("/api/v1/accounts/" + bob.id() + "/follow"), token("alice")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
        mockMvc.perform(withToken(post("/api/v1/accounts/" + alice.id() + "/follow"), token("bob")))
                .andExpect(status().isForbidden());

        mockMvc.perform(withToken(post("/api/v1/accounts/" + bob.id() + "/unblock"), token("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocking").value(false));
        follow("alice", bob);
    }

    @Test
    void blockingTwiceKeepsOneBlock() throws Exception {
        var bob = account("bob");
        block("alice", bob);
        block("alice", bob);

        assertThat(jdbc.sql("SELECT count(*) FROM account_block").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void cantBlockOrMuteItself() throws Exception {
        var alice = account("alice");

        mockMvc.perform(withToken(post("/api/v1/accounts/" + alice.id() + "/block"), token("alice")))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(withToken(post("/api/v1/accounts/" + alice.id() + "/mute"), token("alice")))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void listsBlockedAccountsNewestFirstInPages() throws Exception {
        var bob = account("bob");
        var carol = account("carol");
        block("alice", bob);
        block("alice", carol);

        mockMvc.perform(withToken(get("/api/v1/blocks"), token("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(carol.id().toString()))
                .andExpect(jsonPath("$[1].id").value(bob.id().toString()));
        var response = mockMvc.perform(withToken(get("/api/v1/blocks").param("limit", "1"), token("alice")))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(carol.id().toString()))
                .andExpect(header().string("Link", containsString("rel=\"next\"")))
                .andReturn().getResponse();
        var next = response.getHeader("Link").replaceAll(".*<https://blocks.example([^>]*)>; rel=\"next\".*", "$1");
        mockMvc.perform(withToken(get(URI.create(next)), token("alice")))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(bob.id().toString()));
    }

    @Test
    void hidesTheirStatusesBothWays() throws Exception {
        var bob = account("bob");
        var alice = account("alice");
        follow("alice", bob);
        var bobs = createStatus(token("bob"), "from bob", "public");
        var alices = createStatus(token("alice"), "from alice", "public");
        block("bob", alice);

        assertThat(statusIds(withToken(get("/api/v1/timelines/home"), token("alice")))).contains(alices).doesNotContain(bobs);
        assertThat(statusIds(withToken(get("/api/v1/timelines/public"), token("alice"))))
                .contains(alices).doesNotContain(bobs);
        assertThat(statusIds(withToken(get("/api/v1/timelines/public"), token("bob")))).contains(bobs).doesNotContain(alices);
        assertThat(statusIds(withToken(get("/api/v1/accounts/" + bob.id() + "/statuses"), token("alice")))).isEmpty();
        // The blocked account can't see, favourite, or reply to the blocker's statuses.
        mockMvc.perform(withToken(get("/api/v1/statuses/" + bobs), token("alice"))).andExpect(status().isNotFound());
        mockMvc.perform(withToken(post("/api/v1/statuses/" + bobs + "/favourite"), token("alice")))
                .andExpect(status().isNotFound());
        // Someone not signed in still sees both.
        assertThat(statusIds(get("/api/v1/timelines/public"))).contains(alices, bobs);
    }

    @Test
    void hidesRepliesFromBlockedAccountsInThreads() throws Exception {
        var carol = account("carol");
        var root = createStatus(token("alice"), "root", "public");
        var reply = createReply(token("carol"), "reply", root);
        block("alice", carol);

        mockMvc.perform(withToken(get("/api/v1/statuses/" + root + "/context"), token("alice")))
                .andExpect(jsonPath("$.descendants", hasSize(0)));
        mockMvc.perform(withToken(get("/api/v1/statuses/" + root + "/context"), token("bob")))
                .andExpect(jsonPath("$.descendants[0].id").value(reply));
    }

    @Test
    void removesNotificationsAndKeepsNewOnesOut() throws Exception {
        var bob = account("bob");
        createStatus(token("bob"), "hi @alice", "public");
        await().atMost(WAIT).until(() -> notifications("alice"), count -> count == 1);

        block("alice", bob);

        await().atMost(WAIT).until(() -> notificationRows(), count -> count == 0);
        createStatus(token("bob"), "hi again @alice", "public");
        assertThat(notifications("alice")).isZero();
    }

    @Test
    void blockingARemoteAccountSendsABlockToItsOwnInboxAndEndsTheFollows() throws Exception {
        var alice = account("alice");
        var remote = remote();
        follow("alice", remote);
        var followId = await().atMost(WAIT).until(() -> bodiesOfType(alice, remote, "Follow"), found -> !found.isEmpty())
                .getFirst();
        var theirFollow = remote.uri() + "#follows/1";
        insertFollow(remote, alice, theirFollow);

        block("alice", remote);

        var block = await().atMost(WAIT).until(() -> bodiesOfType(alice, remote, "Block"), found -> !found.isEmpty())
                .getFirst();
        assertThat(JsonPath.<String>read(block, "$.id")).startsWith(alice.uri() + "#blocks/");
        assertThat(JsonPath.<String>read(block, "$.actor")).isEqualTo(alice.uri().toString());
        assertThat(JsonPath.<String>read(block, "$.object")).isEqualTo(remote.uri().toString());
        assertThat(deliveryInboxes(JsonPath.read(block, "$.id"))).containsExactly(remote.inboxUri().toString());
        var undo = await().atMost(WAIT).until(() -> bodiesOfType(alice, remote, "Undo"), found -> !found.isEmpty())
                .getFirst();
        assertThat(JsonPath.<String>read(undo, "$.object.id")).isEqualTo(JsonPath.read(followId, "$.id"));
        var reject = await().atMost(WAIT).until(() -> bodiesOfType(alice, remote, "Reject"), found -> !found.isEmpty())
                .getFirst();
        assertThat(JsonPath.<String>read(reject, "$.object.id")).isEqualTo(theirFollow);
    }

    @Test
    void unblockingARemoteAccountSendsAnUndo() throws Exception {
        var alice = account("alice");
        var remote = remote();
        block("alice", remote);
        var blockId = await().atMost(WAIT).until(() -> bodiesOfType(alice, remote, "Block"), found -> !found.isEmpty())
                .stream().map(body -> JsonPath.<String>read(body, "$.id")).findFirst().orElseThrow();

        mockMvc.perform(withToken(post("/api/v1/accounts/" + remote.id() + "/unblock"), token("alice")))
                .andExpect(status().isOk());

        var undo = await().atMost(WAIT).until(() -> bodiesOfType(alice, remote, "Undo"), found -> !found.isEmpty())
                .getFirst();
        assertThat(JsonPath.<String>read(undo, "$.id")).isEqualTo(blockId + "/undo");
        assertThat(JsonPath.<String>read(undo, "$.object.type")).isEqualTo("Block");
        assertThat(deliveryInboxes(blockId + "/undo")).containsExactly(remote.inboxUri().toString());
    }

    // Mutes

    @Test
    void mutingHidesStatusesButKeepsFollows() throws Exception {
        var bob = account("bob");
        follow("alice", bob);
        var bobs = createStatus(token("bob"), "from bob", "public");
        var alices = createStatus(token("alice"), "from alice", "public");

        mockMvc.perform(withToken(post("/api/v1/accounts/" + bob.id() + "/mute"), token("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.muting").value(true))
                .andExpect(jsonPath("$.muting_notifications").value(true))
                .andExpect(jsonPath("$.following").value(true));

        assertThat(statusIds(withToken(get("/api/v1/timelines/home"), token("alice")))).contains(alices).doesNotContain(bobs);
        assertThat(statusIds(withToken(get("/api/v1/timelines/public"), token("alice"))))
                .contains(alices).doesNotContain(bobs);
        assertThat(jdbc.sql("SELECT count(*) FROM outbound_activity WHERE body LIKE '%mute%'").query(Long.class).single())
                .isZero();
    }

    @Test
    void mutingWithoutNotificationsKeepsThem() throws Exception {
        var bob = account("bob");
        mockMvc.perform(withToken(post("/api/v1/accounts/" + bob.id() + "/mute"), token("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notifications\":false}"))
                .andExpect(jsonPath("$.muting").value(true))
                .andExpect(jsonPath("$.muting_notifications").value(false));

        createStatus(token("bob"), "hi @alice", "public");

        await().atMost(WAIT).until(() -> notifications("alice"), count -> count == 1);
    }

    @Test
    void mutingAgainChangesTheMute() throws Exception {
        var bob = account("bob");
        mute("alice", bob, "true", null);
        mute("alice", bob, "false", "3600");

        assertThat(jdbc.sql("SELECT count(*) FROM account_mute").query(Long.class).single()).isEqualTo(1);
        mockMvc.perform(withToken(get("/api/v1/mutes"), token("alice")))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(bob.id().toString()))
                .andExpect(jsonPath("$[0].username").value("bob"))
                .andExpect(jsonPath("$[0].mute_expires_at").isString());
    }

    @Test
    void anExpiredMuteStopsHiding() throws Exception {
        var bob = account("bob");
        var bobs = createStatus(token("bob"), "from bob", "public");
        mute("alice", bob, null, "60");
        jdbc.sql("UPDATE account_mute SET expires_at = now() - interval '1 minute'").update();

        assertThat(statusIds(withToken(get("/api/v1/timelines/public"), token("alice")))).contains(bobs);
        mockMvc.perform(withToken(get("/api/v1/mutes"), token("alice"))).andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(withToken(get("/api/v1/accounts/relationships").param("id", bob.id().toString()), token("alice")))
                .andExpect(jsonPath("$[0].muting").value(false));
    }

    @Test
    void refusesADurationThatIsntANumberOfSeconds() throws Exception {
        var bob = account("bob");

        mockMvc.perform(withToken(post("/api/v1/accounts/" + bob.id() + "/mute"), token("alice")).param("duration", "-1"))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(withToken(post("/api/v1/accounts/" + bob.id() + "/mute"), token("alice")).param("duration", "soon"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void unmuting() throws Exception {
        var bob = account("bob");
        mute("alice", bob, null, null);

        mockMvc.perform(withToken(post("/api/v1/accounts/" + bob.id() + "/unmute"), token("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.muting").value(false));
    }

    // Server blocks

    @Test
    void blocksAServerAndListsIt() throws Exception {
        mockMvc.perform(withToken(post("/api/v1/domain_blocks"), token("alice")).param("domain", "Example.COM."))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(post("/api/v1/domain_blocks"), token("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"other.example\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(withToken(get("/api/v1/domain_blocks"), token("alice")))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0]").value("other.example"))
                .andExpect(jsonPath("$[1]").value("example.com"));

        mockMvc.perform(withToken(delete("/api/v1/domain_blocks"), token("alice")).param("domain", "example.com"))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(get("/api/v1/domain_blocks"), token("alice"))).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void refusesDomainsThatCantBeBlocked() throws Exception {
        for (var domain : List.of("", "com", "192.0.2.1", "blocks.example", "*.example.com")) {
            mockMvc.perform(withToken(post("/api/v1/domain_blocks"), token("alice")).param("domain", domain))
                    .andExpect(status().isUnprocessableContent());
        }
    }

    @Test
    void blockingAServerRemovesFollowersThereAndHidesItsAccounts() throws Exception {
        var alice = account("alice");
        var remote = remote();
        var theirFollow = remote.uri() + "#follows/1";
        insertFollow(remote, alice, theirFollow);
        var sub = remote("sub." + remote.domain());

        mockMvc.perform(withToken(post("/api/v1/domain_blocks"), token("alice")).param("domain", remote.domain()))
                .andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT count(*) FROM follow").query(Long.class).single()).isZero();
        var reject = await().atMost(WAIT).until(() -> bodiesOfType(alice, remote, "Reject"), found -> !found.isEmpty())
                .getFirst();
        assertThat(JsonPath.<String>read(reject, "$.object.id")).isEqualTo(theirFollow);
        mockMvc.perform(withToken(get("/api/v1/accounts/relationships")
                        .param("id[]", remote.id().toString(), sub.id().toString()), token("alice")))
                .andExpect(jsonPath("$[0].domain_blocking").value(true))
                .andExpect(jsonPath("$[1].domain_blocking").value(true));
        mockMvc.perform(withToken(post("/api/v1/accounts/" + sub.id() + "/follow"), token("alice")))
                .andExpect(status().isForbidden());
    }

    // Helpers

    private void follow(String username, Actor followed) throws Exception {
        mockMvc.perform(withToken(post("/api/v1/accounts/" + followed.id() + "/follow"), token(username)))
                .andExpect(status().isOk());
    }

    private void block(String username, Actor blocked) throws Exception {
        mockMvc.perform(withToken(post("/api/v1/accounts/" + blocked.id() + "/block"), token(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocking").value(true));
    }

    private void mute(String username, Actor muted, String notifications, String duration) throws Exception {
        var request = withToken(post("/api/v1/accounts/" + muted.id() + "/mute"), token(username));
        if (notifications != null) {
            request.param("notifications", notifications);
        }
        if (duration != null) {
            request.param("duration", duration);
        }
        mockMvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.muting").value(true));
    }

    private void insertFollow(Actor follower, Actor followed, String activityUri) {
        jdbc.sql("""
                        INSERT INTO follow (id, follower_id, followed_id, state, activity_uri)
                        VALUES (:id, :follower, :followed, 'accepted', :activity)
                        """)
                .param("id", UUID.randomUUID())
                .param("follower", follower.id())
                .param("followed", followed.id())
                .param("activity", activityUri)
                .update();
    }

    private Actor remote() {
        return remote("h" + UUID.randomUUID() + ".test");
    }

    private Actor remote(String domain) {
        var uri = URI.create("https://" + domain + "/users/" + UUID.randomUUID());
        return actors.upsertRemote(new RemoteActorProfile(
                uri, "dave", domain, URI.create(uri + "/inbox"), null, URI.create("https://" + domain + "/inbox"), null,
                null));
    }

    private Actor account(String username) throws Exception {
        token(username);
        return actors.findLocalByUsername(username).orElseThrow();
    }

    private String createStatus(String token, String text, String visibility) throws Exception {
        var body = mockMvc.perform(withToken(post("/api/v1/statuses"), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", text)
                        .param("visibility", visibility))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String createReply(String token, String text, String parent) throws Exception {
        var body = mockMvc.perform(withToken(post("/api/v1/statuses"), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", text)
                        .param("in_reply_to_id", parent))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private List<String> statusIds(MockHttpServletRequestBuilder request) throws Exception {
        var body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[*].id");
    }

    private long notifications(String username) throws Exception {
        var body = mockMvc.perform(withToken(get("/api/v1/notifications"), token(username)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.<List<Object>>read(body, "$").size();
    }

    private long notificationRows() {
        return jdbc.sql("SELECT count(*) FROM inbox_notification").query(Long.class).single();
    }

    /**
     * @return the activities of this type queued as one account that mention another
     */
    private List<String> bodiesOfType(Actor actor, Actor mentioned, String type) {
        return jdbc.sql("""
                        SELECT body FROM outbound_activity WHERE actor_id = :actorId AND body LIKE :mentioned
                        ORDER BY created_at
                        """)
                .param("actorId", actor.id())
                .param("mentioned", "%" + mentioned.uri() + "%")
                .query(String.class)
                .list()
                .stream()
                .filter(body -> JsonPath.<String>read(body, "$.type").equals(type))
                .toList();
    }

    private List<String> deliveryInboxes(String activityId) {
        return jdbc.sql("""
                        SELECT convert_from(task_data, 'UTF8')::json ->> 'inbox' FROM scheduled_tasks
                        WHERE task_name = 'activity-delivery'
                          AND convert_from(task_data, 'UTF8')::json ->> 'activityId' = :id
                        """)
                .param("id", activityId)
                .query(String.class)
                .list();
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String token(String username) throws Exception {
        var cached = TOKENS.get(username);
        if (cached != null) {
            return cached;
        }
        mockMvc.perform(post("/signup").with(csrf())
                        .param("invite", TestAccounts.invite(jdbc))
                        .param("username", username)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(status().is3xxRedirection());
        var token = TestOAuth.userToken(mockMvc, username, PASSWORD, "read write");
        TOKENS.put(username, token);
        return token;
    }
}
