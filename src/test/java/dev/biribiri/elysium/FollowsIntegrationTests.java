package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import dev.biribiri.elysium.content.RemoteUrlResolver;
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
import static org.hamcrest.Matchers.not;
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
    @MockitoBean
    private RemoteUrlResolver urls;

    @BeforeEach
    void setUp() {
        TestAccounts.makeSureAnAccountExists(actors);
        jdbc.sql("DELETE FROM follow").update();
        jdbc.sql("UPDATE profile SET manually_approves_followers = false, approves_followed = false").update();
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

    // Follow options

    @Test
    void aFollowStartsWithMastodonsDefaultOptions() throws Exception {
        var dave = account("dave");

        mockMvc.perform(withToken(post("/api/v1/accounts/" + dave.id() + "/follow"), token("alice")))
                .andExpect(jsonPath("$.showing_reblogs").value(true))
                .andExpect(jsonPath("$.notifying").value(false))
                .andExpect(jsonPath("$.languages").isEmpty());
        mockMvc.perform(withToken(get("/api/v1/accounts/relationships").param("id[]", dave.id().toString()), token("erin")))
                .andExpect(jsonPath("$[0].showing_reblogs").value(false))
                .andExpect(jsonPath("$[0].notifying").value(false));
    }

    @Test
    void followingAgainChangesOnlyTheOptionsGiven() throws Exception {
        var dave = account("dave");
        mockMvc.perform(withToken(post("/api/v1/accounts/" + dave.id() + "/follow"), token("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reblogs": false, "languages": ["en", "pt-BR"]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showing_reblogs").value(false))
                .andExpect(jsonPath("$.notifying").value(false))
                .andExpect(jsonPath("$.languages", contains("en", "pt-BR")));

        mockMvc.perform(withToken(post("/api/v1/accounts/" + dave.id() + "/follow"), token("alice"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("notify", "true"))
                .andExpect(jsonPath("$.showing_reblogs").value(false))
                .andExpect(jsonPath("$.notifying").value(true))
                .andExpect(jsonPath("$.languages", contains("en", "pt-BR")));
        mockMvc.perform(withToken(get("/api/v1/accounts/relationships").param("id[]", dave.id().toString()), token("alice")))
                .andExpect(jsonPath("$[0].showing_reblogs").value(false))
                .andExpect(jsonPath("$[0].notifying").value(true))
                .andExpect(jsonPath("$[0].languages", contains("en", "pt-BR")));

        mockMvc.perform(withToken(post("/api/v1/accounts/" + dave.id() + "/follow"), token("alice"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("languages[]", "")
                        .param("reblogs", "true"))
                .andExpect(jsonPath("$.showing_reblogs").value(true))
                .andExpect(jsonPath("$.notifying").value(true))
                .andExpect(jsonPath("$.languages").isEmpty());
        assertThat(jdbc.sql("SELECT count(*) FROM follow").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void refusesALanguageThatIsntACode() throws Exception {
        var dave = account("dave");

        mockMvc.perform(withToken(post("/api/v1/accounts/" + dave.id() + "/follow"), token("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"languages": ["en", "not a language"]}"""))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(withToken(post("/api/v1/accounts/" + dave.id() + "/follow"), token("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"languages": [1]}"""))
                .andExpect(status().isUnprocessableContent());
        assertThat(jdbc.sql("SELECT count(*) FROM follow").query(Long.class).single()).isZero();
    }

    @Test
    void aRequestKeepsItsOptionsOnceApproved() throws Exception {
        var alice = account("alice");
        var erin = account("erin");
        lock("erin", true);
        mockMvc.perform(withToken(post("/api/v1/accounts/" + erin.id() + "/follow"), token("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reblogs": false, "notify": true, "languages": ["de"]}"""))
                .andExpect(jsonPath("$.requested").value(true))
                .andExpect(jsonPath("$.showing_reblogs").value(false))
                .andExpect(jsonPath("$.notifying").value(true));

        mockMvc.perform(withToken(post("/api/v1/follow_requests/" + alice.id() + "/authorize"), token("erin")))
                .andExpect(status().isOk());

        mockMvc.perform(withToken(get("/api/v1/accounts/relationships").param("id[]", erin.id().toString()), token("alice")))
                .andExpect(jsonPath("$[0].following").value(true))
                .andExpect(jsonPath("$[0].showing_reblogs").value(false))
                .andExpect(jsonPath("$[0].notifying").value(true))
                .andExpect(jsonPath("$[0].languages", contains("de")));
    }

    @Test
    void hidingBoostsKeepsThemOutOfHomeAndLists() throws Exception {
        var dave = account("dave");
        var erin = account("erin");
        follow("alice", erin);
        mockMvc.perform(withToken(post("/api/v1/accounts/" + dave.id() + "/follow"), token("alice"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("reblogs", "false"))
                .andExpect(status().isOk());
        var erins = createStatus(token("erin"), "by erin", "public");
        var daves = createStatus(token("dave"), "by dave", "public");
        var davesBoost = reblog(token("dave"), erins);
        var erinsBoost = reblog(token("erin"), daves);
        var alicesBoost = reblog(token("alice"), daves);
        var list = createList(token("alice"), dave);

        assertThat(statusIds(withToken(get("/api/v1/timelines/home"), token("alice"))))
                .contains(erins, daves, erinsBoost, alicesBoost)
                .doesNotContain(davesBoost);
        assertThat(statusIds(withToken(get("/api/v1/timelines/list/" + list), token("alice"))))
                .contains(daves)
                .doesNotContain(davesBoost);
    }

    @Test
    void languagesKeepOtherLanguagesOutOfHome() throws Exception {
        var dave = account("dave");
        var erin = account("erin");
        mockMvc.perform(withToken(post("/api/v1/accounts/" + dave.id() + "/follow"), token("alice"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("languages[]", "en"))
                .andExpect(status().isOk());
        var english = createStatus(token("dave"), "hello", "public", "en");
        var german = createStatus(token("dave"), "hallo", "public", "de");
        var unknown = createStatus(token("dave"), "...", "public", null);
        var erinsGerman = createStatus(token("erin"), "guten Tag", "public", "de");
        var erinsEnglish = createStatus(token("erin"), "good day", "public", "en");
        var boostOfGerman = reblog(token("dave"), erinsGerman);
        var boostOfEnglish = reblog(token("dave"), erinsEnglish);

        assertThat(statusIds(withToken(get("/api/v1/timelines/home"), token("alice"))))
                .contains(english, unknown, boostOfEnglish)
                .doesNotContain(german, boostOfGerman);
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

    // Removing followers

    @Test
    void removingAFollowerEndsTheirFollow() throws Exception {
        var alice = account("alice");
        var bob = account("bob");
        follow("alice", bob);

        mockMvc.perform(withToken(post("/api/v1/accounts/" + alice.id() + "/remove_from_followers"), token("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alice.id().toString()))
                .andExpect(jsonPath("$.followed_by").value(false));

        mockMvc.perform(withToken(get("/api/v1/accounts/relationships").param("id[]", bob.id().toString()), token("alice")))
                .andExpect(jsonPath("$[0].following").value(false))
                .andExpect(jsonPath("$[0].requested").value(false));
        mockMvc.perform(get("/api/v1/accounts/" + bob.id())).andExpect(jsonPath("$.followers_count").value(0));
        follow("alice", bob);
        mockMvc.perform(get("/api/v1/accounts/" + bob.id())).andExpect(jsonPath("$.followers_count").value(1));
    }

    @Test
    void removingFromFollowersLeavesARequestWaiting() throws Exception {
        var alice = account("alice");
        var bob = account("bob");
        lock("bob", true);
        follow("alice", bob);

        mockMvc.perform(withToken(post("/api/v1/accounts/" + alice.id() + "/remove_from_followers"), token("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested_by").value(true));

        mockMvc.perform(withToken(get("/api/v1/follow_requests"), token("bob")))
                .andExpect(jsonPath("$[*].username", contains("alice")));
    }

    @Test
    void removingAnAccountThatIsntAFollowerChangesNothing() throws Exception {
        var alice = account("alice");
        var bob = account("bob");
        follow("bob", alice);

        mockMvc.perform(withToken(post("/api/v1/accounts/" + alice.id() + "/remove_from_followers"), token("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.following").value(true))
                .andExpect(jsonPath("$.followed_by").value(false));
        mockMvc.perform(withToken(post("/api/v1/accounts/" + bob.id() + "/remove_from_followers"), token("bob")))
                .andExpect(status().isOk());
    }

    @Test
    void removingAFollowerNeedsTheScopeAndAnAccount() throws Exception {
        var alice = account("alice");
        mockMvc.perform(withToken(post("/api/v1/accounts/" + alice.id() + "/remove_from_followers"), token("bob", "read")))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(post("/api/v1/accounts/" + UUID.randomUUID() + "/remove_from_followers"), token("bob")))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(post("/api/v1/accounts/nope/remove_from_followers"), token("bob")))
                .andExpect(status().isNotFound());
    }

    @Test
    void removingARemoteFollowerQueuesARejectAndIgnoresTheFollowAfterwards() throws Exception {
        var alice = account("alice");
        var remote = remote();
        insertFollow(remote, alice);
        var theirFollow = jdbc.sql("SELECT activity_uri FROM follow WHERE follower_id = :id")
                .param("id", remote.id())
                .query(String.class)
                .single();

        mockMvc.perform(withToken(post("/api/v1/accounts/" + remote.id() + "/remove_from_followers"), token("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followed_by").value(false));

        var reject = await().atMost(WAIT)
                .until(() -> outboundBodies(alice, remote), bodies -> bodies.stream().anyMatch(body -> body.contains("\"Reject\"")))
                .stream().filter(body -> body.contains("\"Reject\"")).findFirst().orElseThrow();
        assertThat(JsonPath.<String>read(reject, "$.object.id")).isEqualTo(theirFollow);
        assertThat(jdbc.sql("SELECT count(*) FROM undone_follow WHERE activity_uri = :uri")
                .param("uri", theirFollow)
                .query(Long.class)
                .single()).isEqualTo(1);
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

    // Approving followers by hand

    @Test
    void aRequestToFollowALockedAccountWaitsForIt() throws Exception {
        var alice = account("alice");
        var carol = account("carol");
        mockMvc.perform(withToken(patch("/api/v1/accounts/update_credentials"), token("carol"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("locked", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.source.follow_requests_count").value(0));
        var statusId = createStatus(token("carol"), "for followers", "private");

        mockMvc.perform(withToken(post("/api/v1/accounts/" + carol.id() + "/follow"), token("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.following").value(false))
                .andExpect(jsonPath("$.requested").value(true));

        mockMvc.perform(get("/api/v1/accounts/" + carol.id()))
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.followers_count").value(0));
        mockMvc.perform(withToken(get("/api/v1/accounts/verify_credentials"), token("carol")))
                .andExpect(jsonPath("$.source.follow_requests_count").value(1));
        mockMvc.perform(withToken(get("/api/v1/accounts/relationships").param("id[]", alice.id().toString()), token("carol")))
                .andExpect(jsonPath("$[0].followed_by").value(false))
                .andExpect(jsonPath("$[0].requested_by").value(true));
        mockMvc.perform(withToken(get("/api/v1/follow_requests"), token("carol")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username", contains("alice")));
        mockMvc.perform(withToken(get("/api/v1/statuses/" + statusId), token("alice"))).andExpect(status().isNotFound());
        expectStatusIds(withToken(get("/api/v1/accounts/" + carol.id() + "/statuses"), token("alice")));
        mockMvc.perform(get("/users/carol/followers").accept(ACTIVITY_JSON)).andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void approvingARequestMakesAFollower() throws Exception {
        var alice = account("alice");
        var carol = account("carol");
        lock("carol", true);
        var before = createStatus(token("carol"), "before", "private");
        follow("alice", carol);

        mockMvc.perform(withToken(post("/api/v1/follow_requests/" + alice.id() + "/authorize"), token("carol")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alice.id().toString()))
                .andExpect(jsonPath("$.followed_by").value(true))
                .andExpect(jsonPath("$.requested_by").value(false));

        mockMvc.perform(withToken(get("/api/v1/accounts/relationships").param("id[]", carol.id().toString()), token("alice")))
                .andExpect(jsonPath("$[0].following").value(true))
                .andExpect(jsonPath("$[0].requested").value(false));
        mockMvc.perform(get("/api/v1/accounts/" + carol.id())).andExpect(jsonPath("$.followers_count").value(1));
        mockMvc.perform(withToken(get("/api/v1/follow_requests"), token("carol"))).andExpect(jsonPath("$", empty()));
        mockMvc.perform(withToken(get("/api/v1/statuses/" + before), token("alice"))).andExpect(status().isOk());
        mockMvc.perform(withToken(post("/api/v1/follow_requests/" + alice.id() + "/authorize"), token("carol")))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectingARequestRemovesIt() throws Exception {
        var alice = account("alice");
        var bob = account("bob");
        lock("bob", true);
        follow("alice", bob);

        mockMvc.perform(withToken(post("/api/v1/follow_requests/" + alice.id() + "/reject"), token("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followed_by").value(false))
                .andExpect(jsonPath("$.requested_by").value(false));

        mockMvc.perform(withToken(get("/api/v1/accounts/relationships").param("id[]", bob.id().toString()), token("alice")))
                .andExpect(jsonPath("$[0].following").value(false))
                .andExpect(jsonPath("$[0].requested").value(false));
        mockMvc.perform(withToken(post("/api/v1/follow_requests/" + alice.id() + "/reject"), token("bob")))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(post("/api/v1/follow_requests/" + bob.id() + "/authorize"), token("alice")))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(post("/api/v1/follow_requests/not-a-uuid/authorize"), token("bob")))
                .andExpect(status().isNotFound());
    }

    @Test
    void withdrawingARequestRemovesIt() throws Exception {
        var bob = account("bob");
        lock("bob", true);
        follow("alice", bob);
        unfollow("alice", bob);

        mockMvc.perform(withToken(get("/api/v1/follow_requests"), token("bob"))).andExpect(jsonPath("$", empty()));
    }

    @Test
    void lockingKeepsFollowersAndUnlockingApprovesWhoIsWaiting() throws Exception {
        var bob = account("bob");
        follow("alice", bob);
        lock("bob", true);
        follow("carol", bob);
        mockMvc.perform(get("/api/v1/accounts/" + bob.id())).andExpect(jsonPath("$.followers_count").value(1));

        lock("bob", false);

        await().atMost(WAIT).untilAsserted(() -> mockMvc.perform(get("/api/v1/accounts/" + bob.id()))
                .andExpect(jsonPath("$.followers_count").value(2)));
        mockMvc.perform(withToken(get("/api/v1/follow_requests"), token("bob"))).andExpect(jsonPath("$", empty()));
        follow("alice", account("carol"));
        mockMvc.perform(withToken(get("/api/v1/accounts/relationships").param("id[]", account("carol").id().toString()),
                        token("alice")))
                .andExpect(jsonPath("$[0].following").value(true));
    }

    @Test
    void aLockedAccountCanApproveRequestsFromAccountsItFollowsStraightAway() throws Exception {
        var alice = account("alice");
        var carol = account("carol");
        account("bob");
        lock("carol", true);
        follow("carol", alice);
        follow("alice", carol);
        mockMvc.perform(withToken(get("/api/v1/follow_requests"), token("carol")))
                .andExpect(jsonPath("$[*].username", contains("alice")));
        unfollow("alice", carol);

        approveFollowed("carol", true);
        follow("alice", carol);
        follow("bob", carol);

        mockMvc.perform(withToken(get("/api/v1/accounts/relationships").param("id[]", carol.id().toString()), token("alice")))
                .andExpect(jsonPath("$[0].following").value(true))
                .andExpect(jsonPath("$[0].requested").value(false));
        mockMvc.perform(withToken(get("/api/v1/follow_requests"), token("carol")))
                .andExpect(jsonPath("$[*].username", contains("bob")));
    }

    @Test
    void aFollowThatIsStillWaitingDoesntApproveARequest() throws Exception {
        var alice = account("alice");
        var carol = account("carol");
        lock("alice", true);
        lock("carol", true);
        approveFollowed("carol", true);
        follow("carol", alice);

        follow("alice", carol);

        mockMvc.perform(withToken(get("/api/v1/follow_requests"), token("carol")))
                .andExpect(jsonPath("$[*].username", contains("alice")));
    }

    @Test
    void approvingRequestsFromAccountsItFollowsApprovesThoseWaiting() throws Exception {
        var alice = account("alice");
        var carol = account("carol");
        account("bob");
        lock("carol", true);
        follow("alice", carol);
        follow("bob", carol);
        follow("carol", alice);

        approveFollowed("carol", true);

        await().atMost(WAIT).untilAsserted(() -> mockMvc.perform(get("/api/v1/accounts/" + carol.id()))
                .andExpect(jsonPath("$.followers_count").value(1)));
        mockMvc.perform(withToken(get("/api/v1/follow_requests"), token("carol")))
                .andExpect(jsonPath("$[*].username", contains("bob")));
        mockMvc.perform(withToken(get("/api/v1/accounts/relationships").param("id[]", carol.id().toString()), token("alice")))
                .andExpect(jsonPath("$[0].following").value(true));
    }

    @Test
    void pagesThroughFollowRequests() throws Exception {
        var alice = account("alice");
        lock("alice", true);
        follow("bob", alice);
        follow("carol", alice);

        var response = mockMvc.perform(withToken(get("/api/v1/follow_requests").param("limit", "1"), token("alice")))
                .andExpect(jsonPath("$[*].username", contains("carol")))
                .andExpect(header().string("Link", containsString("rel=\"next\"")))
                .andReturn().getResponse();
        var next = response.getHeader("Link").replaceAll(".*<([^>]*)>; rel=\"next\".*", "$1");

        mockMvc.perform(withToken(get(URI.create(next)), token("alice")))
                .andExpect(jsonPath("$[*].username", contains("bob")));
    }

    @Test
    void followRequestsNeedTheScope() throws Exception {
        var alice = account("alice");
        mockMvc.perform(withToken(get("/api/v1/follow_requests"), token("bob", "read:accounts")))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(post("/api/v1/follow_requests/" + alice.id() + "/authorize"), token("bob", "read")))
                .andExpect(status().isForbidden());
    }

    @Test
    void theActorDocumentSaysWhetherFollowersAreApprovedByHand() throws Exception {
        account("alice");
        mockMvc.perform(get("/users/alice").accept(ACTIVITY_JSON))
                .andExpect(jsonPath("$.manuallyApprovesFollowers").value(false));
        lock("alice", true);
        mockMvc.perform(get("/users/alice").accept(ACTIVITY_JSON))
                .andExpect(jsonPath("$.manuallyApprovesFollowers").value(true));
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
        assertThat(JsonPath.<List<String>>read(follow, "$['@context']"))
                .containsExactly("https://www.w3.org/ns/activitystreams", "https://w3id.org/security/data-integrity/v2");
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

    // Familiar followers

    @Test
    void listsTheFollowersThatTheUserFollows() throws Exception {
        var alice = account("alice");
        var bob = account("bob");
        var carol = account("carol");
        follow("alice", bob);
        follow("alice", carol);
        follow("bob", carol);
        follow("carol", alice);
        var stranger = remote();
        insertFollow(stranger, carol);

        mockMvc.perform(withToken(get("/api/v1/accounts/familiar_followers")
                                .param("id[]", carol.id().toString(), "not-an-id", UUID.randomUUID().toString())
                                .param("id", bob.id().toString()),
                        token("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(carol.id().toString()))
                .andExpect(jsonPath("$[0].accounts[*].username", contains("bob")))
                .andExpect(jsonPath("$[1].id").value(bob.id().toString()))
                .andExpect(jsonPath("$[1].accounts", empty()));
    }

    @Test
    void leavesOutFamiliarFollowersOfAnAccountThatHidesThem() throws Exception {
        var alice = account("alice");
        var bob = account("bob");
        var carol = account("carol");
        follow("alice", bob);
        follow("bob", carol);
        follow("bob", alice);
        actors.setHidesCollections(carol.id(), true);
        actors.setHidesCollections(alice.id(), true);

        mockMvc.perform(withToken(get("/api/v1/accounts/familiar_followers")
                                .param("id[]", carol.id().toString(), alice.id().toString()),
                        token("alice")))
                .andExpect(jsonPath("$[0].accounts", empty()))
                .andExpect(jsonPath("$[1].accounts[*].username", contains("bob")));
    }

    @Test
    void familiarFollowersNeedAUser() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/familiar_followers").param("id[]", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
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
                        .param("bot", "true"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("bot")));
        assertThat(actors.hidesCollections(account("alice").id())).isFalse();

        mockMvc.perform(withToken(patch("/api/v1/accounts/update_credentials"), token("alice"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("bot", "false")
                        .param("unknown", ""))
                .andExpect(status().isOk());
    }

    @Test
    void theSettingsPageChangesTheSetting() throws Exception {
        token("alice");
        var session = TestOAuth.logIn(mockMvc, "alice", PASSWORD);

        mockMvc.perform(get("/settings").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"hideCollections\"")))
                .andExpect(content().string(containsString("name=\"manuallyApprovesFollowers\"")))
                .andExpect(content().string(containsString("name=\"approvesFollowed\"")));
        mockMvc.perform(post("/settings").session(session).with(csrf()).param("hideCollections", "true"))
                .andExpect(redirectedUrl("/settings?saved"));
        assertThat(actors.hidesCollections(account("alice").id())).isTrue();

        mockMvc.perform(post("/settings").session(session).with(csrf()))
                .andExpect(redirectedUrl("/settings?saved"));
        assertThat(actors.hidesCollections(account("alice").id())).isFalse();

        mockMvc.perform(post("/settings").session(session).with(csrf()).param("manuallyApprovesFollowers", "true"))
                .andExpect(redirectedUrl("/settings?saved"));
        mockMvc.perform(get("/api/v1/accounts/" + account("alice").id())).andExpect(jsonPath("$.locked").value(true));
    }

    @Test
    void approvingRequestsFromAccountsItFollowsIsntPartOfTheProfile() throws Exception {
        var alice = account("alice");
        var updatedAt = profileUpdatedAt(alice);

        approveFollowed("alice", true);

        assertThat(profileUpdatedAt(alice)).isEqualTo(updatedAt);
        var session = TestOAuth.logIn(mockMvc, "alice", PASSWORD);
        mockMvc.perform(get("/settings").session(session))
                .andExpect(content().string(containsString("name=\"approvesFollowed\" value=\"true\" checked")));

        approveFollowed("alice", false);

        mockMvc.perform(get("/settings").session(session))
                .andExpect(content().string(
                        not(containsString("name=\"approvesFollowed\" value=\"true\" checked"))));
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
        when(urls.resolveUrl(any(), eq(profile))).thenReturn(Optional.of(new RemoteUrlResolver.Found.Account(remote)));

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
        mockMvc.perform(get("/api/v2/search").param("q", "ali").param("offset", "1"))
                .andExpect(jsonPath("$.accounts", empty()));
        mockMvc.perform(get("/api/v2/search").param("q", "ali").param("limit", "40").param("offset", "500"))
                .andExpect(jsonPath("$.accounts", empty()));
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

    private void lock(String username, boolean locked) throws Exception {
        mockMvc.perform(withToken(patch("/api/v1/accounts/update_credentials"), token(username))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locked\":" + locked + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(locked));
    }

    /**
     * Saves the settings form with this and what the account has now, since a box that isn't ticked is sent as off.
     */
    private void approveFollowed(String username, boolean approves) throws Exception {
        token(username);
        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);
        var request = post("/settings").session(session).with(csrf());
        if (actors.hidesCollections(account(username).id())) {
            request.param("hideCollections", "true");
        }
        var locked = jdbc.sql("SELECT manually_approves_followers FROM profile WHERE actor_id = :id")
                .param("id", account(username).id())
                .query(Boolean.class)
                .optional()
                .orElse(false);
        if (locked) {
            request.param("manuallyApprovesFollowers", "true");
        }
        if (approves) {
            request.param("approvesFollowed", "true");
        }
        mockMvc.perform(request).andExpect(redirectedUrl("/settings?saved"));
    }

    private Optional<String> profileUpdatedAt(Actor actor) {
        return jdbc.sql("SELECT updated_at::text FROM profile WHERE actor_id = :id")
                .param("id", actor.id())
                .query(String.class)
                .optional();
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
        return createStatus(token, text, visibility, null);
    }

    private String createStatus(String token, String text, String visibility, String language) throws Exception {
        var request = withToken(post("/api/v1/statuses"), token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("status", text)
                .param("visibility", visibility);
        if (language != null) {
            request.param("language", language);
        }
        var body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /**
     * @return the boost's ID
     */
    private String reblog(String token, String statusId) throws Exception {
        var body = mockMvc.perform(withToken(post("/api/v1/statuses/" + statusId + "/reblog"), token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String createList(String token, Actor member) throws Exception {
        var body = mockMvc.perform(withToken(post("/api/v1/lists"), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("title", "Some"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        mockMvc.perform(withToken(post("/api/v1/lists/" + id + "/accounts"), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("account_ids[]", member.id().toString()))
                .andExpect(status().isOk());
        return id;
    }

    private List<String> statusIds(MockHttpServletRequestBuilder request) throws Exception {
        var body = mockMvc.perform(request.param("limit", "40")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[*].id");
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
                            .param("invite", TestAccounts.invite(jdbc))
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
