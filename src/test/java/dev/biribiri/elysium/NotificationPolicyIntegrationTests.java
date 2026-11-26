package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.RemoteStatuses;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.StatusStored;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.graph.BlockService;
import dev.biribiri.elysium.graph.FollowOptions;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.notification.NotificationAdded;
import dev.biribiri.elysium.notification.NotificationService;
import dev.biribiri.elysium.notification.Notifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static dev.biribiri.elysium.content.Visibility.DIRECT;
import static dev.biribiri.elysium.content.Visibility.PUBLIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.opaqueToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://policy.example", "elysium.jobs.worker-enabled=false"})
@AutoConfigureMockMvc
@RecordApplicationEvents
class NotificationPolicyIntegrationTests {
    private static final String NOTIFICATIONS = "/api/v1/notifications";
    private static final String GROUPS = "/api/v2/notifications";
    private static final String POLICY = "/api/v2/notifications/policy";
    private static final String REQUESTS = "/api/v1/notifications/requests";
    private static final String CONVERSATIONS = "/api/v1/conversations";
    private static final Set<String> SCOPES = Set.of("read:notifications", "write:notifications", "read:statuses",
            "write:statuses");
    @Autowired MockMvc mvc;
    @Autowired ActorService actors;
    @Autowired StatusService statuses;
    @Autowired RemoteStatuses remoteStatuses;
    @Autowired FollowService follows;
    @Autowired LikeService likes;
    @Autowired BlockService blocks;
    @Autowired NotificationService notifications;
    @Autowired Notifier notifier;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationEvents events;
    private Actor alice;
    private Actor bob;

    @BeforeEach
    void setUp() {
        settled();
        jdbc.sql("DELETE FROM conversation").update();
        jdbc.sql("DELETE FROM notification").update();
        jdbc.sql("DELETE FROM notification_request").update();
        jdbc.sql("DELETE FROM notification_permission").update();
        jdbc.sql("DELETE FROM notification_policy").update();
        jdbc.sql("DELETE FROM status").update();
        jdbc.sql("DELETE FROM follow").update();
        jdbc.sql("DELETE FROM account_block").update();
        jdbc.sql("UPDATE profile SET manually_approves_followers = false").update();
        alice = local("policyalice");
        bob = local("policybob");
        // Old enough not to count as new accounts, unless a test says otherwise.
        jdbc.sql("UPDATE local_account SET created_at = now() - INTERVAL '60 days'").update();
        jdbc.sql("UPDATE actor SET created_at = now() - INTERVAL '60 days'").update();
    }

    @Test
    void theDefaultPolicyFiltersOnlyPrivateMentions() throws Exception {
        mvc.perform(get(POLICY).with(user(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.for_not_following").value("accept"))
                .andExpect(jsonPath("$.for_not_followers").value("accept"))
                .andExpect(jsonPath("$.for_new_accounts").value("accept"))
                .andExpect(jsonPath("$.for_private_mentions").value("filter"))
                .andExpect(jsonPath("$.for_limited_accounts").value("filter"))
                .andExpect(jsonPath("$.for_bots").value("accept"))
                .andExpect(jsonPath("$.summary.pending_requests_count").value(0))
                .andExpect(jsonPath("$.summary.pending_notifications_count").value(0));
        createStatus(alice, "@policybob public", PUBLIC, null);
        settled();
        mvc.perform(get(NOTIFICATIONS).with(user(bob))).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void anUnsolicitedDirectMessageWaitsInARequest() throws Exception {
        events.clear();
        var dm = createStatus(alice, "@policybob buy my stuff", DIRECT, null);
        settled();

        mvc.perform(get(NOTIFICATIONS).with(user(bob))).andExpect(jsonPath("$", empty()));
        mvc.perform(get(GROUPS).with(user(bob))).andExpect(jsonPath("$.notification_groups", empty()));
        mvc.perform(get(CONVERSATIONS).with(user(bob))).andExpect(jsonPath("$", empty()));
        mvc.perform(get(NOTIFICATIONS + "/unread_count").with(user(bob))).andExpect(jsonPath("$.count").value(0));
        assertThat(events.stream(NotificationAdded.class).filter(e -> e.recipientId().equals(bob.id()))).isEmpty();
        // The sender's own conversation isn't affected.
        mvc.perform(get(CONVERSATIONS).with(user(alice))).andExpect(jsonPath("$", hasSize(1)));

        mvc.perform(get(REQUESTS).with(user(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].account.id").value(alice.id().toString()))
                .andExpect(jsonPath("$[0].notifications_count").value("1"))
                .andExpect(jsonPath("$[0].last_status.id").value(dm.id().toString()));
        mvc.perform(get(POLICY).with(user(bob)))
                .andExpect(jsonPath("$.summary.pending_requests_count").value(1))
                .andExpect(jsonPath("$.summary.pending_notifications_count").value(1));
        mvc.perform(get(NOTIFICATIONS).param("include_filtered", "true").with(user(bob)))
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get(GROUPS).param("include_filtered", "true").with(user(bob)))
                .andExpect(jsonPath("$.notification_groups", hasSize(1)));
        mvc.perform(get(NOTIFICATIONS).param("account_id", alice.id().toString()).with(user(bob)))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void aDirectMessageFromAFollowedAccountGetsThrough() throws Exception {
        follows.follow(bob, alice);
        createStatus(alice, "@policybob hi", DIRECT, null);
        settled();
        mvc.perform(get(NOTIFICATIONS).with(user(bob))).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get(CONVERSATIONS).with(user(bob))).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get(REQUESTS).with(user(bob))).andExpect(jsonPath("$", empty()));
    }

    @Test
    void anAnswerToTheRecipientsOwnDirectMessageGetsThrough() throws Exception {
        var question = createStatus(bob, "@policyalice question", DIRECT, null);
        var other = createStatus(alice, "@policybob reply", DIRECT, question.id());
        createStatus(alice, "@policybob and more", DIRECT, other.id());
        settled();
        mvc.perform(get(NOTIFICATIONS).with(user(bob))).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get(CONVERSATIONS).with(user(bob))).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get(REQUESTS).with(user(bob))).andExpect(jsonPath("$", empty()));
    }

    @Test
    void aReplyToSomethingThatDidntMentionTheSenderIsStillFiltered() throws Exception {
        var post = createStatus(bob, "a public post", PUBLIC, null);
        createStatus(alice, "@policybob psst", DIRECT, post.id());
        settled();
        mvc.perform(get(NOTIFICATIONS).with(user(bob))).andExpect(jsonPath("$", empty()));
        mvc.perform(get(REQUESTS).with(user(bob))).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void acceptingARequestBringsBackItsNotificationsAndConversationAndLetsTheSenderThrough() throws Exception {
        createStatus(alice, "@policybob first", DIRECT, null);
        createStatus(alice, "@policybob second", DIRECT, null);
        settled();
        String id = values(get(REQUESTS).with(user(bob)), "$[0].id");
        mvc.perform(get(REQUESTS).with(user(bob))).andExpect(jsonPath("$[0].notifications_count").value("2"));
        mvc.perform(get(REQUESTS + "/" + id).with(user(bob))).andExpect(status().isOk());
        events.clear();

        mvc.perform(post(REQUESTS + "/" + id + "/accept").with(user(bob))).andExpect(status().isOk());

        assertThat(events.stream(NotificationAdded.class)).isEmpty();
        mvc.perform(get(NOTIFICATIONS).with(user(bob))).andExpect(jsonPath("$", hasSize(2)));
        // Two threads, so two conversations.
        mvc.perform(get(CONVERSATIONS).with(user(bob))).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get(REQUESTS).with(user(bob))).andExpect(jsonPath("$", empty()));
        mvc.perform(get(REQUESTS + "/" + id).with(user(bob))).andExpect(status().isNotFound());
        mvc.perform(get(REQUESTS + "/merged").with(user(bob))).andExpect(jsonPath("$.merged").value(true));

        createStatus(alice, "@policybob third", DIRECT, null);
        settled();
        mvc.perform(get(NOTIFICATIONS).with(user(bob))).andExpect(jsonPath("$", hasSize(3)));
        mvc.perform(get(REQUESTS).with(user(bob))).andExpect(jsonPath("$", empty()));
    }

    @Test
    void dismissingARequestKeepsItsNotificationsAwayEvenWhenTheirEventsAreRetried() throws Exception {
        var dm = createStatus(alice, "@policybob spam", DIRECT, null);
        settled();
        String id = values(get(REQUESTS).with(user(bob)), "$[0].id");

        mvc.perform(post(REQUESTS + "/" + id + "/dismiss").with(user(bob))).andExpect(status().isOk());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> notifier.on(new StatusStored(dm.id())));

        mvc.perform(get(REQUESTS).with(user(bob))).andExpect(jsonPath("$", empty()));
        mvc.perform(get(NOTIFICATIONS).param("include_filtered", "true").with(user(bob))).andExpect(jsonPath("$", empty()));
        mvc.perform(post(REQUESTS + "/" + id + "/dismiss").with(user(bob))).andExpect(status().isNotFound());

        // A later message makes a new request, since dismissing doesn't let the sender through.
        createStatus(alice, "@policybob more spam", DIRECT, null);
        settled();
        mvc.perform(get(REQUESTS).with(user(bob))).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get(NOTIFICATIONS).with(user(bob))).andExpect(jsonPath("$", empty()));
    }

    @Test
    void severalRequestsCanBeAnsweredAtOnce() throws Exception {
        var carol = local("policycarol");
        var dave = local("policydave");
        createStatus(alice, "@policybob one", DIRECT, null);
        createStatus(carol, "@policybob two", DIRECT, null);
        createStatus(dave, "@policybob three", DIRECT, null);
        settled();
        var requests = mvc.perform(get(REQUESTS).with(user(bob))).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(requests, "$[*].id");
        List<String> senders = JsonPath.read(requests, "$[*].account.id");
        assertThat(ids).hasSize(3);
        var aliceRequest = ids.get(senders.indexOf(alice.id().toString()));
        var carolRequest = ids.get(senders.indexOf(carol.id().toString()));

        mvc.perform(post(REQUESTS + "/accept").param("id[]", aliceRequest).param("id[]", "nonsense").with(user(bob)))
                .andExpect(status().isOk());
        mvc.perform(post(REQUESTS + "/dismiss").param("id[]", carolRequest).with(user(bob))).andExpect(status().isOk());

        mvc.perform(get(REQUESTS).with(user(bob)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].account.id").value(dave.id().toString()));
        mvc.perform(get(NOTIFICATIONS).with(user(bob)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].account.id").value(alice.id().toString()));
    }

    @Test
    void requestsArePagedNewestFirst() throws Exception {
        var carol = local("policycarol");
        createStatus(alice, "@policybob one", DIRECT, null);
        settled();
        createStatus(carol, "@policybob two", DIRECT, null);
        settled();
        mvc.perform(get(REQUESTS).param("limit", "1").with(user(bob)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].account.id").value(carol.id().toString()));
        String newest = values(get(REQUESTS).with(user(bob)), "$[0].id");
        mvc.perform(get(REQUESTS).param("max_id", newest).with(user(bob)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].account.id").value(alice.id().toString()));
    }

    @Test
    void thePolicyCanBeChangedByJsonOrForm() throws Exception {
        mvc.perform(patch(POLICY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"for_not_following\": \"filter\", \"for_bots\": \"drop\"}").with(user(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.for_not_following").value("filter"))
                .andExpect(jsonPath("$.for_bots").value("drop"))
                .andExpect(jsonPath("$.for_private_mentions").value("filter"));
        mvc.perform(patch(POLICY).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("for_private_mentions", "accept").with(user(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.for_not_following").value("filter"))
                .andExpect(jsonPath("$.for_private_mentions").value("accept"));
        mvc.perform(patch(POLICY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"for_new_accounts\": \"maybe\"}").with(user(bob)))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(get(POLICY).with(user(alice))).andExpect(jsonPath("$.for_not_following").value("accept"));
    }

    @Test
    void notFollowingFiltersOrDropsEveryFilterableType() throws Exception {
        policy(bob, "for_not_following", "filter");
        createStatus(alice, "@policybob public mention", PUBLIC, null);
        follows.follow(alice, bob);
        settled();
        mvc.perform(get(NOTIFICATIONS).with(user(bob))).andExpect(jsonPath("$", empty()));
        mvc.perform(get(NOTIFICATIONS).param("include_filtered", "true").with(user(bob))).andExpect(jsonPath("$", hasSize(2)));
        // Only mentions and quotes make a request, as on Mastodon.
        mvc.perform(get(REQUESTS).with(user(bob))).andExpect(jsonPath("$[0].notifications_count").value("1"));

        policy(bob, "for_not_following", "drop");
        var carol = local("policycarol");
        createStatus(carol, "@policybob dropped", PUBLIC, null);
        settled();
        mvc.perform(get(NOTIFICATIONS).param("include_filtered", "true").with(user(bob))).andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void aFollowerOfLessThanThreeDaysIsNotYetAFollower() throws Exception {
        policy(bob, "for_not_followers", "filter");
        follows.follow(alice, bob);
        createStatus(alice, "@policybob hello", PUBLIC, null);
        settled();
        mvc.perform(get(NOTIFICATIONS).with(user(bob))).andExpect(jsonPath("$", empty()));

        jdbc.sql("UPDATE follow SET created_at = now() - INTERVAL '4 days'").update();
        createStatus(alice, "@policybob again", PUBLIC, null);
        settled();
        mvc.perform(get(NOTIFICATIONS).with(user(bob))).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void aFollowerCountsFromWhenItsRequestWasApproved() throws Exception {
        policy(bob, "for_not_followers", "filter");
        jdbc.sql("""
                        INSERT INTO profile (actor_id, manually_approves_followers) VALUES (:id, true)
                        ON CONFLICT (actor_id) DO UPDATE SET manually_approves_followers = true
                        """).param("id", bob.id()).update();
        follows.follow(alice, bob);
        // The request has waited 5 days, but the follow only starts when it's approved.
        jdbc.sql("UPDATE follow SET created_at = now() - INTERVAL '5 days'").update();
        assertThat(follows.authorize(alice, bob.id())).isTrue();
        createStatus(alice, "@policybob just approved", PUBLIC, null);
        settled();
        mvc.perform(get(NOTIFICATIONS).with(user(bob))).andExpect(jsonPath("$", empty()));
    }

    @Test
    void filteredGroupsAreKeptApartAndCanBeOpenedAndDismissed() throws Exception {
        policy(bob, "for_not_following", "filter");
        var post = createStatus(bob, "a post", PUBLIC, null);
        var carol = local("policycarol");
        follows.follow(bob, carol);
        likes.favourite(post.id(), alice.id());
        likes.favourite(post.id(), carol.id());
        settled();

        mvc.perform(get(GROUPS).with(user(bob)))
                .andExpect(jsonPath("$.notification_groups", hasSize(1)))
                .andExpect(jsonPath("$.notification_groups[0].notifications_count").value(1));
        var listed = mvc.perform(get(GROUPS).param("include_filtered", "true").with(user(bob)))
                .andExpect(jsonPath("$.notification_groups", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        List<String> keys = JsonPath.read(listed, "$.notification_groups[*].group_key");
        var filtered = keys.stream().filter(key -> key.startsWith("filtered-")).findFirst().orElseThrow();

        mvc.perform(get(GROUPS + "/" + filtered).with(user(bob))).andExpect(status().isOk());
        mvc.perform(get(GROUPS + "/" + filtered + "/accounts").with(user(bob)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(alice.id().toString()));
        mvc.perform(post(GROUPS + "/" + filtered + "/dismiss").with(user(bob))).andExpect(status().isOk());
        mvc.perform(get(GROUPS).param("include_filtered", "true").with(user(bob)))
                .andExpect(jsonPath("$.notification_groups", hasSize(1)));
    }

    @Test
    void acceptingARequestPutsItsNotificationsBackInTheirGroups() throws Exception {
        policy(bob, "for_not_following", "filter");
        var post = createStatus(bob, "a post", PUBLIC, null);
        createStatus(alice, "@policybob makes a request", PUBLIC, null);
        likes.favourite(post.id(), alice.id());
        settled();
        String id = values(get(REQUESTS).with(user(bob)), "$[0].id");
        mvc.perform(post(REQUESTS + "/" + id + "/accept").with(user(bob))).andExpect(status().isOk());
        mvc.perform(get(GROUPS).with(user(bob)))
                .andExpect(jsonPath("$.notification_groups", hasSize(2)))
                .andExpect(jsonPath("$.notification_groups[*].group_key", hasItem("favourite-" + post.id())));
    }

    @Test
    void newAccountsAreThoseLessThanThirtyDaysOld() throws Exception {
        policy(bob, "for_new_accounts", "filter");
        var remote = remote("new.example");
        store(remote, Map.of(bob.id(), false), PUBLIC);
        settled();
        mvc.perform(get(NOTIFICATIONS).with(user(bob))).andExpect(jsonPath("$", empty()));

        jdbc.sql("UPDATE actor SET created_at = now() - INTERVAL '60 days' WHERE id = :id").param("id", remote.id()).update();
        store(remote, Map.of(bob.id(), false), PUBLIC);
        settled();
        mvc.perform(get(NOTIFICATIONS).with(user(bob))).andExpect(jsonPath("$", hasSize(1)));

        // A follow exempts a new account, as on Mastodon.
        jdbc.sql("UPDATE local_account SET created_at = now() WHERE actor_id = :id").param("id", alice.id()).update();
        follows.follow(bob, alice);
        createStatus(alice, "@policybob from a new account", PUBLIC, null);
        settled();
        mvc.perform(get(NOTIFICATIONS).with(user(bob))).andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void theStrictestPolicyThatAppliesWins() throws Exception {
        policy(bob, "for_not_following", "filter");
        policy(bob, "for_private_mentions", "drop");
        createStatus(alice, "@policybob dropped", DIRECT, null);
        createStatus(alice, "@policybob filtered", PUBLIC, null);
        settled();
        mvc.perform(get(NOTIFICATIONS).param("include_filtered", "true").with(user(bob)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status.visibility").value("public"));
        mvc.perform(get(CONVERSATIONS).with(user(bob))).andExpect(jsonPath("$", empty()));
    }

    @Test
    void anAdminsMentionsAlwaysGetThrough() throws Exception {
        // Its own account, since the role outlasts the test.
        var admin = local("policyadmin");
        jdbc.sql("""
                        INSERT INTO account_credential (actor_id, password_hash, role) VALUES (:id, 'unused', 'ADMIN')
                        ON CONFLICT (actor_id) DO UPDATE SET role = 'ADMIN'
                        """).param("id", admin.id()).update();
        policy(bob, "for_not_following", "drop");
        createStatus(admin, "@policybob from the admin", DIRECT, null);
        settled();
        mvc.perform(get(NOTIFICATIONS).with(user(bob))).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get(CONVERSATIONS).with(user(bob))).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void typesThatCantBeFilteredGetThrough() throws Exception {
        policy(bob, "for_not_followers", "drop");
        follows.follow(bob, alice, new FollowOptions(Optional.empty(), Optional.of(true), Optional.empty()));
        createStatus(alice, "for my subscribers", PUBLIC, null);
        createStatus(alice, "@policybob but not this", PUBLIC, null);
        settled();
        // Each status notifies its subscriber, while the mention is dropped.
        mvc.perform(get(NOTIFICATIONS).param("include_filtered", "true").with(user(bob)))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].type", everyItem(is("status"))));
    }

    @Test
    void blockingTheSenderRemovesItsRequest() throws Exception {
        createStatus(alice, "@policybob spam", DIRECT, null);
        settled();
        blocks.block(bob, alice);
        settled();
        mvc.perform(get(REQUESTS).with(user(bob))).andExpect(jsonPath("$", empty()));
        assertThat(jdbc.sql("SELECT count(*) FROM notification_request").query(Long.class).single()).isZero();
    }

    @Test
    void deletingTheSenderRemovesItsRequestAndPermission() throws Exception {
        var remote = remote("gone.example");
        store(remote, Map.of(bob.id(), false), DIRECT);
        settled();
        String id = values(get(REQUESTS).with(user(bob)), "$[0].id");
        mvc.perform(post(REQUESTS + "/" + id + "/accept").with(user(bob))).andExpect(status().isOk());
        store(remote, Map.of(bob.id(), false), DIRECT);
        settled();

        notifications.removeForActor(remote.id());

        assertThat(jdbc.sql("SELECT count(*) FROM notification_permission").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM notification_request").query(Long.class).single()).isZero();
    }

    private void policy(Actor account, String name, String value) throws Exception {
        mvc.perform(patch(POLICY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"" + name + "\": \"" + value + "\"}").with(user(account)))
                .andExpect(status().isOk());
    }

    private Actor local(String name) {
        return actors.findLocalByUsername(name).orElseGet(() -> actors.createLocalIfAbsent(name, actor -> {}).orElseThrow());
    }

    private Actor remote(String domain) {
        var uri = URI.create("https://" + domain + "/users/" + UUID.randomUUID());
        return actors.upsertRemote(new RemoteActorProfile(uri, "remote", domain, URI.create(uri + "/inbox"), null, null, null));
    }

    private Status createStatus(Actor author, String text, Visibility visibility, UUID parent) {
        return statuses.create(author.id(), new NewStatus(text, null, null, visibility, false, parent));
    }

    private UUID store(Actor remote, Map<UUID, Boolean> mentions, Visibility visibility) {
        var uri = URI.create(remote.uri() + "/notes/" + UUID.randomUUID());
        return remoteStatuses.storeRemote(new RemoteStatus(uri, null, remote.id(), "<p>message</p>", null, null,
                visibility, false, null, Instant.now(), null, mentions)).orElseThrow();
    }

    private RequestPostProcessor user(Actor actor) {
        return opaqueToken().principal(new TokenPrincipal("test", Optional.of(actor.id()), SCOPES,
                Instant.now(), Instant.now().plusSeconds(3600), "test"));
    }

    private <T> T values(MockHttpServletRequestBuilder request, String path) throws Exception {
        return JsonPath.read(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), path);
    }

    private void settled() {
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql("SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0);
    }
}
