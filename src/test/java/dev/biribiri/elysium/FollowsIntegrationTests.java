package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import dev.biribiri.elysium.moderation.DomainBlocks;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Follows, and the lists and collections they make, through the client API and the ActivityPub endpoints. No job worker
 * runs, so activities are only queued. Answers from other servers are tested in {@code FollowAnswerIntegrationTests}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://follows.example",
        "elysium.registration.enabled=true",
        "elysium.jobs.worker-enabled=false"
})
@AutoConfigureMockMvc
class FollowsIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String ACTIVITY_JSON = "application/activity+json";
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
    @Autowired
    private DomainBlocks blocks;
    @MockitoBean
    private RemoteActorResolver resolver;

    @BeforeEach
    void setUp() {
        TestAccounts.makeSureAnAccountExists(actors);
        jdbc.sql("DELETE FROM follow").update();
        for (var username : List.of("alice", "bob", "carol")) {
            actors.findLocalByUsername(username).ifPresent(actor -> actors.setHidesCollections(actor.id(), false));
        }
    }

    // Following local accounts

    @Test
    void followingALocalAccountIsAcceptedStraightAway() throws Exception {
        var bob = account("bob");

        mockMvc.perform(withToken(post("/api/v1/accounts/" + bob.id() + "/follow"), token("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bob.id().toString()))
                .andExpect(jsonPath("$.following").value(true))
                .andExpect(jsonPath("$.requested").value(false))
                .andExpect(jsonPath("$.followed_by").value(false));

        mockMvc.perform(withToken(get("/api/v1/accounts/relationships").param("id[]", account("alice").id().toString()),
                        token("bob")))
                .andExpect(jsonPath("$[0].following").value(false))
                .andExpect(jsonPath("$[0].followed_by").value(true));
        mockMvc.perform(get("/api/v1/accounts/" + bob.id()))
                .andExpect(jsonPath("$.followers_count").value(1))
                .andExpect(jsonPath("$.following_count").value(0))
                .andExpect(jsonPath("$.hide_collections").value(false));
        mockMvc.perform(get("/api/v1/accounts/" + account("alice").id()))
                .andExpect(jsonPath("$.following_count").value(1));
        assertThat(outboundBodies(account("alice"), bob)).isEmpty();
    }

    @Test
    void followingAgainChangesNothing() throws Exception {
        var bob = account("bob");
        follow("alice", bob);
        follow("alice", bob);

        assertThat(jdbc.sql("SELECT count(*) FROM follow").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void unfollowingALocalAccount() throws Exception {
        var bob = account("bob");
        follow("alice", bob);

        mockMvc.perform(withToken(post("/api/v1/accounts/" + bob.id() + "/unfollow"), token("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.following").value(false));
        mockMvc.perform(get("/api/v1/accounts/" + bob.id())).andExpect(jsonPath("$.followers_count").value(0));
    }

    @Test
    void cantFollowItself() throws Exception {
        mockMvc.perform(withToken(post("/api/v1/accounts/" + account("alice").id() + "/follow"), token("alice")))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void cantFollowAnAccountThatDoesntExist() throws Exception {
        mockMvc.perform(withToken(post("/api/v1/accounts/" + UUID.randomUUID() + "/follow"), token("alice")))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(post("/api/v1/accounts/nope/follow"), token("alice")))
                .andExpect(status().isNotFound());
    }

    @Test
    void followingNeedsTheScope() throws Exception {
        mockMvc.perform(withToken(post("/api/v1/accounts/" + account("bob").id() + "/follow"), token("alice", "read")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/accounts/" + account("bob").id() + "/follow"))
                .andExpect(status().isUnauthorized());
    }

    // Followers-only statuses

    @Test
    void followersCanSeeFollowersOnlyStatuses() throws Exception {
        var bob = account("bob");
        var statusId = createStatus(token("bob"), "for followers", "private");

        mockMvc.perform(withToken(get("/api/v1/statuses/" + statusId), token("alice"))).andExpect(status().isNotFound());
        expectStatusIds(withToken(get("/api/v1/accounts/" + bob.id() + "/statuses"), token("alice")));

        follow("alice", bob);

        mockMvc.perform(withToken(get("/api/v1/statuses/" + statusId), token("alice"))).andExpect(status().isOk());
        expectStatusIds(withToken(get("/api/v1/accounts/" + bob.id() + "/statuses"), token("alice")), statusId);
        mockMvc.perform(withToken(get("/api/v1/statuses/" + statusId), token("carol"))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/statuses/" + statusId)).andExpect(status().isNotFound());
        var direct = createStatus(token("bob"), "only me", "direct");
        mockMvc.perform(withToken(get("/api/v1/statuses/" + direct), token("alice"))).andExpect(status().isNotFound());
    }

    // Following remote accounts

    @Test
    void followingARemoteAccountWaitsForAnAnswerAndQueuesAFollow() throws Exception {
        var remote = remote();
        var alice = account("alice");

        mockMvc.perform(withToken(post("/api/v1/accounts/" + remote.id() + "/follow"), token("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.following").value(false))
                .andExpect(jsonPath("$.requested").value(true));

        var follow = await().atMost(WAIT).until(() -> outboundBodies(alice, remote), bodies -> !bodies.isEmpty()).getFirst();
        assertThat(JsonPath.<String>read(follow, "$.type")).isEqualTo("Follow");
        assertThat(JsonPath.<String>read(follow, "$.id")).startsWith(alice.uri() + "#follows/");
        assertThat(JsonPath.<String>read(follow, "$.actor")).isEqualTo(alice.uri().toString());
        assertThat(JsonPath.<String>read(follow, "$.object")).isEqualTo(remote.uri().toString());
        assertThat(JsonPath.<String>read(follow, "$['@context']")).isEqualTo("https://www.w3.org/ns/activitystreams");
        assertThat(deliveryInboxes(alice, remote)).containsExactly(remote.inboxUri().toString());
        mockMvc.perform(get("/api/v1/accounts/" + remote.id()))
                .andExpect(jsonPath("$.acct").value("bob@" + remote.domain()))
                .andExpect(jsonPath("$.followers_count").value(0))
                .andExpect(jsonPath("$.hide_collections").doesNotExist());
    }

    @Test
    void unfollowingARemoteAccountCancelsTheFollowAndQueuesAnUndo() throws Exception {
        var remote = remote();
        var alice = account("alice");
        follow("alice", remote);
        var followId = await().atMost(WAIT).until(() -> outboundBodies(alice, remote), bodies -> !bodies.isEmpty())
                .stream().map(body -> JsonPath.<String>read(body, "$.id")).findFirst().orElseThrow();

        mockMvc.perform(withToken(post("/api/v1/accounts/" + remote.id() + "/unfollow"), token("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(false));

        var undo = await().atMost(WAIT)
                .until(() -> outboundBodies(alice, remote), bodies -> bodies.stream().anyMatch(body -> body.contains("\"Undo\"")))
                .stream().filter(body -> body.contains("\"Undo\"")).findFirst().orElseThrow();
        assertThat(JsonPath.<String>read(undo, "$.id")).isEqualTo(followId + "/undo");
        assertThat(JsonPath.<String>read(undo, "$.object.id")).isEqualTo(followId);
        assertThat(JsonPath.<String>read(undo, "$.object.object")).isEqualTo(remote.uri().toString());
        // The Follow hadn't been sent, so it never will be.
        assertThat(followIds(alice, remote)).isEmpty();
    }

    @Test
    void followingAgainAfterUnfollowingSendsANewFollow() throws Exception {
        var remote = remote();
        var alice = account("alice");
        follow("alice", remote);
        var first = await().atMost(WAIT).until(() -> followIds(alice, remote), ids -> ids.size() == 1).getFirst();
        unfollow("alice", remote);
        follow("alice", remote);

        var ids = await().atMost(WAIT).until(() -> followIds(alice, remote), found -> found.size() == 1 && !found.contains(first));
        assertThat(ids.getFirst()).startsWith(alice.uri() + "#follows/");
    }

    @Test
    void cantFollowAnAccountOnABlockedServer() throws Exception {
        var remote = remote();
        blocks.create(remote.domain(), null);

        mockMvc.perform(withToken(post("/api/v1/accounts/" + remote.id() + "/follow"), token("alice")))
                .andExpect(status().isUnprocessableContent());
        assertThat(jdbc.sql("SELECT count(*) FROM follow").query(Long.class).single()).isZero();
    }

    // Lists of followers and following

    @Test
    void listsFollowersAndFollowingNewestFirst() throws Exception {
        var alice = account("alice");
        follow("bob", alice);
        follow("carol", alice);
        follow("alice", account("carol"));

        mockMvc.perform(get("/api/v1/accounts/" + alice.id() + "/followers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username", contains("carol", "bob")));
        mockMvc.perform(get("/api/v1/accounts/" + alice.id() + "/following"))
                .andExpect(jsonPath("$[*].username", contains("carol")));
    }

    @Test
    void pagesThroughFollowers() throws Exception {
        var alice = account("alice");
        follow("bob", alice);
        follow("carol", alice);

        var response = mockMvc.perform(get("/api/v1/accounts/" + alice.id() + "/followers").param("limit", "1"))
                .andExpect(jsonPath("$[*].username", contains("carol")))
                .andExpect(header().string("Link", containsString("rel=\"next\"")))
                .andReturn().getResponse();
        var next = response.getHeader("Link").replaceAll(".*<([^>]*)>; rel=\"next\".*", "$1");

        mockMvc.perform(get(URI.create(next))).andExpect(jsonPath("$[*].username", contains("bob")));
    }

    // Hiding collections

    @Test
    void hidesFollowersFromEveryoneButTheAccount() throws Exception {
        var alice = account("alice");
        follow("bob", alice);
        follow("alice", account("bob"));

        mockMvc.perform(withToken(patch("/api/v1/accounts/update_credentials"), token("alice"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("hide_collections", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hide_collections").value(true))
                .andExpect(jsonPath("$.source").exists());

        mockMvc.perform(get("/api/v1/accounts/" + alice.id() + "/followers")).andExpect(jsonPath("$", empty()));
        mockMvc.perform(withToken(get("/api/v1/accounts/" + alice.id() + "/following"), token("bob")))
                .andExpect(jsonPath("$", empty()));
        mockMvc.perform(withToken(get("/api/v1/accounts/" + alice.id() + "/followers"), token("alice")))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/v1/accounts/" + alice.id()))
                .andExpect(jsonPath("$.followers_count").value(1))
                .andExpect(jsonPath("$.following_count").value(1));

        mockMvc.perform(get("/users/alice/followers").accept(ACTIVITY_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.first").doesNotExist());
        mockMvc.perform(get("/users/alice/following").param("page", "true").accept(ACTIVITY_JSON))
                .andExpect(jsonPath("$.type").value("OrderedCollection"))
                .andExpect(jsonPath("$.orderedItems").doesNotExist());
    }

    @Test
    void updatesTheSettingFromJson() throws Exception {
        mockMvc.perform(withToken(patch("/api/v1/accounts/update_credentials"), token("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hide_collections\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hide_collections").value(true));
        assertThat(actors.hidesCollections(account("alice").id())).isTrue();

        mockMvc.perform(withToken(patch("/api/v1/accounts/update_credentials"), token("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hide_collections\":false}"))
                .andExpect(jsonPath("$.hide_collections").value(false));
    }

    @Test
    void refusesToChangeWhatCantBeChangedYet() throws Exception {
        mockMvc.perform(withToken(patch("/api/v1/accounts/update_credentials"), token("alice"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("hide_collections", "true")
                        .param("display_name", "Alice"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("display_name")));
        assertThat(actors.hidesCollections(account("alice").id())).isFalse();

        mockMvc.perform(withToken(patch("/api/v1/accounts/update_credentials"), token("alice"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("note", ""))
                .andExpect(status().isOk());
    }

    @Test
    void theSettingsPageChangesTheSetting() throws Exception {
        token("alice");
        var session = TestOAuth.logIn(mockMvc, "alice", PASSWORD);

        mockMvc.perform(get("/settings").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"hideCollections\"")));
        mockMvc.perform(post("/settings").session(session).with(csrf()).param("hideCollections", "true"))
                .andExpect(redirectedUrl("/settings?saved"));
        assertThat(actors.hidesCollections(account("alice").id())).isTrue();

        mockMvc.perform(post("/settings").session(session).with(csrf()))
                .andExpect(redirectedUrl("/settings?saved"));
        assertThat(actors.hidesCollections(account("alice").id())).isFalse();
    }

    @Test
    void theSettingsPageNeedsALogin() throws Exception {
        mockMvc.perform(get("/settings")).andExpect(redirectedUrl("/login"));
        mockMvc.perform(post("/settings").with(csrf()).param("hideCollections", "true"))
                .andExpect(status().isForbidden());
    }

    // ActivityPub collections

    @Test
    void servesCollectionsWithPages() throws Exception {
        var alice = account("alice");
        follow("bob", alice);
        follow("carol", alice);

        mockMvc.perform(get("/users/alice").accept(ACTIVITY_JSON))
                .andExpect(jsonPath("$.followers").value(alice.uri() + "/followers"))
                .andExpect(jsonPath("$.following").value(alice.uri() + "/following"));
        mockMvc.perform(get("/users/alice/followers").accept(ACTIVITY_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("OrderedCollection"))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.first").value(alice.uri() + "/followers?page=true"));
        mockMvc.perform(get("/users/alice/followers").param("page", "true").accept(ACTIVITY_JSON))
                .andExpect(jsonPath("$.type").value("OrderedCollectionPage"))
                .andExpect(jsonPath("$.partOf").value(alice.uri() + "/followers"))
                .andExpect(jsonPath("$.orderedItems", contains(
                        account("carol").uri().toString(), account("bob").uri().toString())))
                .andExpect(jsonPath("$.next").doesNotExist());
        mockMvc.perform(get("/users/alice/following").accept(ACTIVITY_JSON))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void pagesLinkToTheNext() throws Exception {
        var alice = account("alice");
        var followers = new java.util.ArrayList<Actor>();
        for (var i = 0; i < 41; i++) {
            followers.add(remote());
        }
        for (var follower : followers) {
            insertFollow(follower, alice);
        }

        var first = mockMvc.perform(get("/users/alice/followers").param("page", "true").accept(ACTIVITY_JSON))
                .andExpect(jsonPath("$.orderedItems", hasSize(40)))
                .andExpect(jsonPath("$.totalItems").value(41))
                .andExpect(jsonPath("$.next", startsWith(alice.uri() + "/followers?page=true&max_id=")))
                .andReturn().getResponse().getContentAsString();
        var next = URI.create(JsonPath.<String>read(first, "$.next"));

        mockMvc.perform(get(next).accept(ACTIVITY_JSON))
                .andExpect(jsonPath("$.id").value(next.toString()))
                .andExpect(jsonPath("$.orderedItems", contains(followers.getFirst().uri().toString())))
                .andExpect(jsonPath("$.next").doesNotExist());
        mockMvc.perform(get("/users/alice/followers").param("page", "true").param("max_id", "nope").accept(ACTIVITY_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void leavesOutSuspendedAccounts() throws Exception {
        var alice = account("alice");
        var suspended = remote();
        insertFollow(suspended, alice);
        follow("bob", alice);
        actors.suspend(suspended.id());

        mockMvc.perform(get("/users/alice/followers").param("page", "true").accept(ACTIVITY_JSON))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.orderedItems", contains(account("bob").uri().toString())));
    }

    // Finding accounts

    @Test
    void looksUpLocalAccounts() throws Exception {
        var alice = account("alice");

        mockMvc.perform(get("/api/v1/accounts/lookup").param("acct", "alice"))
                .andExpect(jsonPath("$.id").value(alice.id().toString()));
        mockMvc.perform(get("/api/v1/accounts/lookup").param("acct", "@alice@follows.example"))
                .andExpect(jsonPath("$.id").value(alice.id().toString()));
        mockMvc.perform(get("/api/v1/accounts/lookup").param("acct", "nobody"))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyAsksOtherServersForASignedInUser() throws Exception {
        var remote = remote();
        when(resolver.resolveAcct(any(), eq("bob"), eq(remote.domain()))).thenReturn(Optional.of(remote));

        mockMvc.perform(get("/api/v1/accounts/lookup").param("acct", "bob@" + remote.domain()))
                .andExpect(jsonPath("$.id").value(remote.id().toString()));
        verify(resolver, never()).resolveAcct(any(), any(), any());

        mockMvc.perform(withToken(get("/api/v1/accounts/lookup").param("acct", "bob@" + remote.domain()), token("alice")))
                .andExpect(jsonPath("$.id").value(remote.id().toString()));
        verify(resolver).resolveAcct(account("alice").id(), "bob", remote.domain());
    }

    @Test
    void searchesForAnAddress() throws Exception {
        var remote = remote();
        when(resolver.resolveAcct(any(), eq("bob"), eq(remote.domain()))).thenReturn(Optional.of(remote));

        mockMvc.perform(withToken(get("/api/v2/search")
                                .param("q", "@bob@" + remote.domain())
                                .param("resolve", "true"),
                        token("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts[*].id", contains(remote.id().toString())))
                .andExpect(jsonPath("$.statuses", empty()))
                .andExpect(jsonPath("$.hashtags", empty()));
    }

    @Test
    void searchesForAUrl() throws Exception {
        var remote = remote();
        var profile = URI.create("https://" + remote.domain() + "/@bob");
        when(resolver.resolveUrl(any(), eq(profile))).thenReturn(Optional.of(remote));

        mockMvc.perform(withToken(get("/api/v2/search").param("q", profile.toString()).param("resolve", "true"),
                        token("alice")))
                .andExpect(jsonPath("$.accounts[*].id", contains(remote.id().toString())));
        mockMvc.perform(withToken(get("/api/v2/search").param("q", profile.toString()), token("alice")))
                .andExpect(jsonPath("$.accounts", empty()));
    }

    @Test
    void searchesUsernamesByPrefix() throws Exception {
        account("alice");

        mockMvc.perform(get("/api/v2/search").param("q", "ali"))
                .andExpect(jsonPath("$.accounts[*].username", contains("alice")));
        mockMvc.perform(get("/api/v2/search").param("q", "ali").param("type", "statuses"))
                .andExpect(jsonPath("$.accounts", empty()));
    }

    @Test
    void saysWhenThereHaveBeenTooManyLookups() throws Exception {
        when(resolver.resolveAcct(any(), any(), any())).thenThrow(new RemoteActorResolver.ResolveLimitException());

        mockMvc.perform(withToken(get("/api/v1/accounts/lookup").param("acct", "bob@limited.example"), token("alice")))
                .andExpect(status().isTooManyRequests());
    }

    // Helpers

    private void follow(String username, Actor followed) throws Exception {
        mockMvc.perform(withToken(post("/api/v1/accounts/" + followed.id() + "/follow"), token(username)))
                .andExpect(status().isOk());
    }

    private void unfollow(String username, Actor followed) throws Exception {
        mockMvc.perform(withToken(post("/api/v1/accounts/" + followed.id() + "/unfollow"), token(username)))
                .andExpect(status().isOk());
    }

    private void insertFollow(Actor follower, Actor followed) {
        jdbc.sql("""
                        INSERT INTO follow (id, follower_id, followed_id, state, activity_uri)
                        VALUES (:id, :follower, :followed, 'accepted', :activity)
                        """)
                .param("id", UUID.randomUUID())
                .param("follower", follower.id())
                .param("followed", followed.id())
                .param("activity", follower.uri() + "#follow/" + UUID.randomUUID())
                .update();
    }

    private Actor remote() {
        var domain = "h" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + domain + "/users/" + UUID.randomUUID());
        return actors.upsertRemote(new RemoteActorProfile(uri, "bob", domain, URI.create(uri + "/inbox"), null, null, null));
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

    private void expectStatusIds(MockHttpServletRequestBuilder request, String... ids) throws Exception {
        var body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> found = JsonPath.read(body, "$[*].id");
        assertThat(found).containsExactly(ids);
    }

    /**
     * @return the activities queued as one account that mention another, which each test makes anew
     */
    private List<String> outboundBodies(Actor actor, Actor mentioned) {
        return jdbc.sql("""
                        SELECT body FROM outbound_activity WHERE actor_id = :actorId AND body LIKE :mentioned
                        ORDER BY created_at
                        """)
                .param("actorId", actor.id())
                .param("mentioned", "%" + mentioned.uri() + "%")
                .query(String.class)
                .list();
    }

    private List<String> followIds(Actor actor, Actor mentioned) {
        return outboundBodies(actor, mentioned).stream()
                .filter(body -> JsonPath.<String>read(body, "$.type").equals("Follow"))
                .map(body -> JsonPath.<String>read(body, "$.id"))
                .toList();
    }

    private List<String> deliveryInboxes(Actor actor, Actor followed) {
        return jdbc.sql("""
                        SELECT convert_from(task_data, 'UTF8')::json ->> 'inbox' FROM scheduled_tasks
                        WHERE task_name = 'activity-delivery' AND convert_from(task_data, 'UTF8') LIKE :id
                          AND convert_from(task_data, 'UTF8') LIKE :inbox
                        """)
                .param("id", "%" + actor.uri() + "#follows/%")
                .param("inbox", "%" + followed.inboxUri() + "%")
                .query(String.class)
                .list();
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String token(String username) throws Exception {
        return token(username, "read write");
    }

    private String token(String username, String scope) throws Exception {
        var cached = TOKENS.get(username + " " + scope);
        if (cached != null) {
            return cached;
        }
        if (TOKENS.keySet().stream().noneMatch(key -> key.startsWith(username + " "))) {
            mockMvc.perform(post("/signup").with(csrf())
                            .param("username", username)
                            .param("password", PASSWORD)
                            .param("confirmPassword", PASSWORD))
                    .andExpect(status().is3xxRedirection());
        }
        var token = TestOAuth.userToken(mockMvc, username, PASSWORD, scope);
        TOKENS.put(username + " " + scope, token);
        return token;
    }
}
