package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.StatusStored;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.inbox.InboxProjection;
import dev.biribiri.elysium.inbox.InboxService;
import dev.biribiri.elysium.inbox.internal.MarkerRepository;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static dev.biribiri.elysium.content.Visibility.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.opaqueToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://inbox.example", "elysium.jobs.worker-enabled=false"})
@AutoConfigureMockMvc
class InboxIntegrationTests {
    private static final String NOTIFICATIONS = "/api/v1/notifications";
    private static final String GROUPS = "/api/v2/notifications";
    private static final String CONVERSATIONS = "/api/v1/conversations";
    private static final String MARKERS = "/api/v1/markers";
    private static final Set<String> SCOPES = Set.of("read:notifications", "write:notifications", "read:statuses",
            "write:statuses", "write:conversations");
    @Autowired MockMvc mvc;
    @Autowired ActorService actors;
    @Autowired StatusService statuses;
    @Autowired FollowService follows;
    @Autowired InboxService inbox;
    @Autowired InboxProjection projection;
    @Autowired DomainBlocks blocks;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean MarkerRepository markerRepository;
    private Actor alice;
    private Actor bob;
    private Actor carol;

    @BeforeEach
    void setUp() {
        settled();
        jdbc.sql("DELETE FROM inbox_conversation").update();
        jdbc.sql("DELETE FROM inbox_notification").update();
        jdbc.sql("DELETE FROM inbox_marker").update();
        jdbc.sql("DELETE FROM inbox_follow_group").update();
        jdbc.sql("DELETE FROM status").update();
        jdbc.sql("DELETE FROM status_thread_alias").update();
        jdbc.sql("DELETE FROM follow").update();
        jdbc.sql("DELETE FROM domain_block").update();
        alice = local("inboxalice");
        bob = local("inboxbob");
        carol = local("inboxcarol");
    }

    @Test
    void publicMentionsReachNotificationsWithoutFollowing() throws Exception {
        var status = createStatus(alice, "@inboxbob hi", PUBLIC, null);
        settled();
        mvc.perform(get(NOTIFICATIONS).with(user(bob)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].type").value("mention"))
                .andExpect(jsonPath("$[0].account.id").value(alice.id().toString()))
                .andExpect(jsonPath("$[0].status.id").value(status.id().toString()))
                .andExpect(jsonPath("$[0].created_at").isString());
        mvc.perform(get("/api/v1/timelines/home").with(user(bob))).andExpect(jsonPath("$", empty()));
        mvc.perform(get(NOTIFICATIONS).with(user(alice))).andExpect(jsonPath("$", empty()));
        String id = values(get(NOTIFICATIONS).with(user(bob)), "$[0].id");
        mvc.perform(get(NOTIFICATIONS + "/" + id).with(user(bob))).andExpect(status().isOk());
        mvc.perform(get(NOTIFICATIONS + "/" + id).with(user(carol))).andExpect(status().isNotFound());
    }

    @Test
    void silentAndSelfMentionsDoNotNotifyOrCreateRecipientConversations() throws Exception {
        var remote = remote("silent.example");
        store(remote, note(remote), null, Map.of(bob.id(), true), DIRECT);
        createStatus(alice, "@inboxalice only me", DIRECT, null);
        settled();
        mvc.perform(get(NOTIFICATIONS).with(user(bob))).andExpect(jsonPath("$", empty()));
        mvc.perform(get(CONVERSATIONS).with(user(bob))).andExpect(jsonPath("$", empty()));
        mvc.perform(get(NOTIFICATIONS).with(user(alice))).andExpect(jsonPath("$", empty()));
        mvc.perform(get(CONVERSATIONS).with(user(alice))).andExpect(jsonPath("$[0].unread").value(false));
    }

    @Test
    void repeatedCreatesAndListenerRetriesDoNotRestoreDismissedNotifications() throws Exception {
        var remote = remote("repeat.example");
        var uri = note(remote);
        var first = store(remote, uri, null, Map.of(bob.id(), false), PUBLIC);
        settled();
        String id = values(get(NOTIFICATIONS).with(user(bob)), "$[0].id");
        mvc.perform(post(NOTIFICATIONS + "/" + id + "/dismiss").with(user(bob))).andExpect(content().json("{}"));
        assertThat(statuses.storeRemote(new RemoteStatus(uri, null, remote.id(), "new text", null, null,
                PUBLIC, false, null, Instant.now(), null, Map.of(bob.id(), false)))).isEmpty();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> projection.on(new StatusStored(first)));
        settled();
        mvc.perform(get(NOTIFICATIONS).with(user(bob))).andExpect(jsonPath("$", empty()));
        assertThat(jdbc.sql("SELECT count(*) FROM inbox_notification WHERE source_id = :id")
                .param("id", first).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void filtersAndCursorLinksPreserveArraysAndUseClosestNewerResults() throws Exception {
        var ids = new ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            var status = createStatus(alice, "@inboxbob message " + i, PUBLIC, null);
            settled();
            ids.add(jdbc.sql("SELECT id::text FROM inbox_notification WHERE source_id = :id")
                    .param("id", status.id()).query(String.class).single());
        }
        follows.follow(carol, bob);
        settled();
        mvc.perform(get(NOTIFICATIONS).with(user(bob)).param("types[]", "mention", "follow")
                        .param("exclude_types[]", "follow").param("limit", "2").param("account_id", alice.id().toString()))
                .andExpect(jsonPath("$[*].id").value(contains(ids.get(4), ids.get(3))))
                .andExpect(header().string("Link", allOf(containsString("types%5B%5D=mention"), containsString("types%5B%5D=follow"),
                        containsString("exclude_types%5B%5D=follow"), containsString("account_id=" + alice.id()))));
        mvc.perform(get(NOTIFICATIONS).with(user(bob)).param("types[]", "mention").param("limit", "2")
                        .param("min_id", ids.getFirst()).param("since_id", ids.get(3)).param("max_id", ids.get(4)))
                .andExpect(jsonPath("$[*].id").value(contains(ids.get(2), ids.get(1))));
        mvc.perform(get(NOTIFICATIONS).with(user(bob)).param("types[]", "future-type"))
                .andExpect(jsonPath("$", empty()));
        mvc.perform(get(NOTIFICATIONS).with(user(bob)).param("max_id", "invalid"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.error").exists());
    }

    @Test
    void followNotificationsAreGroupedAndHavePartialAccountExpansion() throws Exception {
        follows.follow(alice, bob);
        follows.follow(carol, bob);
        follows.follow(alice, bob);
        settled();
        mvc.perform(get(NOTIFICATIONS).with(user(bob))).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get(GROUPS).with(user(bob)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notification_groups", hasSize(1)))
                .andExpect(jsonPath("$.notification_groups[0].notifications_count").value(2))
                .andExpect(jsonPath("$.notification_groups[0].type").value("follow"))
                .andExpect(jsonPath("$.accounts", hasSize(2))).andExpect(jsonPath("$.statuses", empty()))
                .andExpect(jsonPath("$.notification_groups[0].page_min_id").isString());
        mvc.perform(get(GROUPS).with(user(bob)).param("expand_accounts", "partial_avatars"))
                .andExpect(jsonPath("$.accounts", hasSize(1))).andExpect(jsonPath("$.partial_accounts", hasSize(1)))
                .andExpect(jsonPath("$.partial_accounts[0].avatar_static").isString());
        mvc.perform(get(GROUPS).with(user(bob)).param("grouped_types[]", "mention"))
                .andExpect(jsonPath("$.notification_groups", hasSize(2)))
                .andExpect(jsonPath("$.notification_groups[0].group_key", startsWith("ungrouped-")));
        String key = values(get(GROUPS).with(user(bob)), "$.notification_groups[0].group_key");
        mvc.perform(get(GROUPS + "/" + key).with(user(bob)))
                .andExpect(jsonPath("$.notification_groups[0].notifications_count").value(2))
                .andExpect(jsonPath("$.notification_groups[0].page_min_id").doesNotExist());
        mvc.perform(get(GROUPS + "/" + key + "/accounts").with(user(bob))).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get(GROUPS + "/" + key).with(user(alice))).andExpect(status().isNotFound());
        mvc.perform(post(GROUPS + "/" + key + "/dismiss").with(user(bob))).andExpect(content().json("{}"));
        mvc.perform(get(NOTIFICATIONS).with(user(bob))).andExpect(jsonPath("$", empty()));
    }

    @Test
    void groupedPaginationDoesNotReturnEmptyPagesOfRepeatedGroupMembers() throws Exception {
        follows.follow(alice, bob);
        settled();
        var mention = createStatus(carol, "@inboxbob middle", PUBLIC, null);
        settled();
        follows.follow(carol, bob);
        settled();
        String mentionNotification = jdbc.sql("SELECT id::text FROM inbox_notification WHERE source_id = :id")
                .param("id", mention.id()).query(String.class).single();
        mvc.perform(get(GROUPS).with(user(bob)).param("limit", "2"))
                .andExpect(jsonPath("$.notification_groups", hasSize(2)))
                .andExpect(jsonPath("$.notification_groups[0].type").value("follow"))
                .andExpect(jsonPath("$.notification_groups[1].type").value("mention"));
        mvc.perform(get(GROUPS).with(user(bob)).param("limit", "1").param("max_id", mentionNotification))
                .andExpect(jsonPath("$.notification_groups[0].type").value("follow"));
    }

    @Test
    void notificationMarkersControlCountsAndDoNotDismissAnything() throws Exception {
        follows.follow(alice, bob);
        follows.follow(carol, bob);
        createStatus(alice, "@inboxbob hello", DIRECT, null);
        settled();
        mvc.perform(get(NOTIFICATIONS + "/unread_count").with(user(bob))).andExpect(jsonPath("$.count").value(3));
        mvc.perform(get(GROUPS + "/unread_count").with(user(bob))).andExpect(jsonPath("$.count").value(2));
        mvc.perform(get(NOTIFICATIONS + "/unread_count").with(user(bob)).param("limit", "1"))
                .andExpect(jsonPath("$.count").value(1));
        String latest = values(get(NOTIFICATIONS).with(user(bob)), "$[0].id");
        mvc.perform(post(MARKERS).with(user(bob)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notifications\":{\"last_read_id\":\"" + latest + "\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notifications.version").value(0))
                .andExpect(jsonPath("$.notifications.updated_at").isString());
        mvc.perform(get(NOTIFICATIONS + "/unread_count").with(user(bob))).andExpect(jsonPath("$.count").value(0));
        mvc.perform(get(GROUPS + "/unread_count").with(user(bob))).andExpect(jsonPath("$.count").value(0));
        mvc.perform(get(NOTIFICATIONS).with(user(bob))).andExpect(jsonPath("$", hasSize(3)));
        mvc.perform(get(CONVERSATIONS).with(user(bob))).andExpect(jsonPath("$[0].unread").value(true));
        mvc.perform(get(MARKERS).with(user(bob))).andExpect(content().json("{}"));
        mvc.perform(get(MARKERS).with(user(bob)).param("timeline[]", "notifications"))
                .andExpect(jsonPath("$.notifications.last_read_id").value(latest));
        mvc.perform(post(MARKERS).with(user(bob)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("notifications[last_read_id]", latest).param("home[last_read_id]", latest))
                .andExpect(jsonPath("$.notifications.version").value(1)).andExpect(jsonPath("$.home.version").value(0));
        mvc.perform(multipart(MARKERS).with(user(bob)).param("home[last_read_id]", latest))
                .andExpect(status().isOk()).andExpect(jsonPath("$.home.version").value(1));
    }

    @Test
    void threadsAndParticipantSetsDefineSeparateConversations() throws Exception {
        var root = createStatus(alice, "@inboxbob one", DIRECT, null);
        var reply = createStatus(bob, "@inboxalice reply", DIRECT, root.id());
        createStatus(alice, "@inboxbob separate", DIRECT, null);
        createStatus(alice, "@inboxbob @inboxcarol group", DIRECT, root.id());
        settled();
        mvc.perform(get(CONVERSATIONS).with(user(bob))).andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].accounts", hasSize(2)))
                .andExpect(jsonPath("$[2].last_status.id").value(reply.id().toString()))
                .andExpect(jsonPath("$[2].unread").value(false));
        mvc.perform(get(CONVERSATIONS).with(user(carol))).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get(CONVERSATIONS).with(user(alice))).andExpect(jsonPath("$[2].unread").value(true));
    }

    @Test
    void conversationReadAndDeleteArePerUserAndReplaysDoNotUnhide() throws Exception {
        var root = createStatus(alice, "@inboxbob one", DIRECT, null);
        settled();
        String bobConversation = values(get(CONVERSATIONS).with(user(bob)), "$[0].id");
        mvc.perform(post(CONVERSATIONS + "/" + bobConversation + "/read").with(user(carol))).andExpect(status().isNotFound());
        mvc.perform(post(CONVERSATIONS + "/" + bobConversation + "/read").with(user(bob)))
                .andExpect(jsonPath("$.unread").value(false));
        mvc.perform(get(NOTIFICATIONS + "/unread_count").with(user(bob))).andExpect(jsonPath("$.count").value(1));
        mvc.perform(delete(CONVERSATIONS + "/" + bobConversation).with(user(bob))).andExpect(content().json("{}"));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> projection.on(new StatusStored(root.id())));
        settled();
        mvc.perform(get(CONVERSATIONS).with(user(bob))).andExpect(jsonPath("$", empty()));
        mvc.perform(get(CONVERSATIONS).with(user(alice))).andExpect(jsonPath("$", hasSize(1)));
        var next = createStatus(alice, "@inboxbob two", DIRECT, root.id());
        settled();
        mvc.perform(get(CONVERSATIONS).with(user(bob)))
                .andExpect(jsonPath("$[0].id").value(bobConversation)).andExpect(jsonPath("$[0].unread").value(true))
                .andExpect(jsonPath("$[0].last_status.id").value(next.id().toString()));
    }

    @Test
    void conversationPaginationUsesLatestStatusRatherThanConversationId() throws Exception {
        var first = createStatus(alice, "@inboxbob one", DIRECT, null);
        var second = createStatus(alice, "@inboxbob two", DIRECT, null);
        settled();
        mvc.perform(get(CONVERSATIONS).with(user(bob)).param("limit", "1"))
                .andExpect(header().string("Link", containsString("max_id=" + second.id())));
        mvc.perform(get(CONVERSATIONS).with(user(bob)).param("max_id", second.id().toString()))
                .andExpect(jsonPath("$[0].last_status.id").value(first.id().toString()));
    }

    @Test
    void lateRemoteParentsMergeThreadsAndRootDeletionDoesNotSplitReplies() throws Exception {
        var remote = remote("late.example");
        var rootUri = note(remote);
        var middleUri = note(remote);
        var child = store(remote, note(remote), middleUri, Map.of(bob.id(), false), DIRECT);
        var root = store(remote, rootUri, null, Map.of(bob.id(), false), DIRECT);
        settled();
        mvc.perform(get(CONVERSATIONS).with(user(bob))).andExpect(jsonPath("$", hasSize(2)));
        var middle = store(remote, middleUri, rootUri, Map.of(bob.id(), false), DIRECT);
        settled();
        mvc.perform(get(CONVERSATIONS).with(user(bob))).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].last_status.id").value(middle.toString()));
        statuses.deleteRemote(rootUri, remote.uri());
        settled();
        var another = store(remote, note(remote), rootUri, Map.of(bob.id(), false), DIRECT);
        settled();
        mvc.perform(get(CONVERSATIONS).with(user(bob))).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].last_status.id").value(another.toString()));
        assertThat(jdbc.sql("SELECT count(DISTINCT thread_id) FROM status WHERE id IN (:ids)")
                .param("ids", List.of(child, middle, another)).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void suspendedAndDomainBlockedAuthorsDisappearBeforePagingAndCounting() throws Exception {
        var remote = remote("social.blocked.example:8443");
        var id = store(remote, note(remote), null, Map.of(bob.id(), false), DIRECT);
        createStatus(alice, "@inboxbob keep", PUBLIC, null);
        settled();
        actors.suspend(remote.id());
        settled();
        mvc.perform(get(NOTIFICATIONS).with(user(bob)).param("limit", "1"))
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].account.id").value(alice.id().toString()));
        mvc.perform(get(NOTIFICATIONS + "/unread_count").with(user(bob))).andExpect(jsonPath("$.count").value(1));
        mvc.perform(get(CONVERSATIONS).with(user(bob))).andExpect(jsonPath("$", empty()));
        actors.unsuspend(remote.id());
        settled();
        mvc.perform(get(CONVERSATIONS).with(user(bob))).andExpect(jsonPath("$", hasSize(1)));
        blocks.create("blocked.example", null);
        settled();
        mvc.perform(get(NOTIFICATIONS + "/unread_count").with(user(bob))).andExpect(jsonPath("$.count").value(1));
        mvc.perform(get(CONVERSATIONS).with(user(bob))).andExpect(jsonPath("$", empty()));
        inbox.removeForDomain(remote.domain());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> projection.on(new StatusStored(id)));
        assertThat(jdbc.sql("SELECT count(*) FROM inbox_notification WHERE sender_id = :id")
                .param("id", remote.id()).query(Long.class).single()).isZero();
    }

    @Test
    void homeShowsDirectMessagesOnlyFromFollowedAuthorsAndAlwaysKeepsOwn() throws Exception {
        var received = createStatus(alice, "@inboxbob hi", DIRECT, null);
        var own = createStatus(bob, "@inboxcarol mine", DIRECT, null);
        settled();
        mvc.perform(get("/api/v1/timelines/home").with(user(bob))).andExpect(jsonPath("$[*].id").value(contains(own.id().toString())));
        follows.follow(bob, alice);
        settled();
        mvc.perform(get("/api/v1/timelines/home").with(user(bob)))
                .andExpect(jsonPath("$[*].id").value(contains(own.id().toString(), received.id().toString())));
    }

    @Test
    void newEndpointsRequireScopesAndUserTokens() throws Exception {
        for (var path : List.of(NOTIFICATIONS, NOTIFICATIONS + "/unread_count", GROUPS, GROUPS + "/unread_count", CONVERSATIONS, MARKERS)) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(scoped(bob.id(), Set.of()))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(scoped(null, SCOPES))).andExpect(status().isUnprocessableContent());
        }
        mvc.perform(post(NOTIFICATIONS + "/clear").with(scoped(bob.id(), Set.of("read:notifications"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(NOTIFICATIONS + "/bad").with(user(bob))).andExpect(status().isNotFound());
        mvc.perform(get(CONVERSATIONS).with(user(bob)).param("limit", "bad")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void concurrentNotificationsHaveUniqueOrderedPollingCursors() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = IntStream.range(0, 12).<java.util.concurrent.Callable<Status>>mapToObj(i ->
                    () -> createStatus(alice, "@inboxbob concurrent " + i, PUBLIC, null)).toList();
            for (var result : executor.invokeAll(jobs)) result.get();
        }
        settled();
        List<String> ids = values(get(NOTIFICATIONS).with(user(bob)), "$[*].id");
        assertThat(ids).hasSize(12).doesNotHaveDuplicates().isSortedAccordingTo(java.util.Comparator.reverseOrder());
        mvc.perform(get(NOTIFICATIONS).with(user(bob)).param("since_id", ids.get(6)))
                .andExpect(jsonPath("$[*].id").value(contains(ids.subList(0, 6).toArray())));
    }

    @Test
    void concurrentMarkerUpdatesReturnAConflictWithoutLosingTheWinner() throws Exception {
        var first = UUID.randomUUID();
        inbox.saveMarkers(bob.id(), Map.of("notifications", first));
        var readers = new java.util.concurrent.CountDownLatch(2);
        var remaining = new java.util.concurrent.atomic.AtomicInteger(2);
        org.mockito.Mockito.doAnswer(call -> {
            var snapshot = call.callRealMethod();
            if (remaining.getAndDecrement() > 0) {
                readers.countDown();
                assertThat(readers.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
            return snapshot;
        }).when(markerRepository).find(bob.id(), List.of("notifications"));
        var next = UUID.randomUUID().toString();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Integer> write = () -> mvc.perform(post(MARKERS).with(user(bob))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"notifications\":{\"last_read_id\":\"" + next + "\"}}"))
                    .andReturn().getResponse().getStatus();
            var results = executor.invokeAll(List.of(write, write));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(200, 409);
        }
        mvc.perform(get(MARKERS).with(user(bob)).param("timeline[]", "notifications"))
                .andExpect(jsonPath("$.notifications.last_read_id").value(next))
                .andExpect(jsonPath("$.notifications.version").value(1));
    }

    @Test
    void delayedPreDeletionEventsCannotUnhideAConversation() throws Exception {
        var root = createStatus(alice, "@inboxbob first", DIRECT, null);
        var delayed = createStatus(alice, "@inboxbob delayed", DIRECT, root.id());
        settled();
        String conversation = values(get(CONVERSATIONS).with(user(bob)), "$[0].id");
        jdbc.sql("DELETE FROM inbox_conversation_status WHERE conversation_id = :conversation AND status_id = :status")
                .param("conversation", UUID.fromString(conversation)).param("status", delayed.id()).update();
        mvc.perform(delete(CONVERSATIONS + "/" + conversation).with(user(bob))).andExpect(status().isOk());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> projection.on(new StatusStored(delayed.id())));
        mvc.perform(get(CONVERSATIONS).with(user(bob))).andExpect(jsonPath("$", empty()));
    }

    @Test
    void actorDeletionCanRaceBothStatusAndFollowProjectionsWithoutDeadlocking() {
        for (var i = 0; i < 20; i++) {
            var sender = remote("deleting.example");
            store(sender, note(sender), null, Map.of(bob.id(), false), DIRECT);
            var now = Instant.now();
            new TransactionTemplate(transactions).executeWithoutResult(tx -> follows.accept(sender.id(), sender.uri(), bob.id(),
                    sender.uri() + "/follow", new FollowService.Arrival(now, now)));
            jdbc.sql("DELETE FROM actor WHERE id = :id").param("id", sender.id()).update();
        }
        settled();
        assertThat(jdbc.sql("SELECT count(*) FROM inbox_notification WHERE recipient_id = :id")
                .param("id", bob.id()).query(Long.class).single()).isZero();
    }

    @Test
    void followGroupsStopReusingABucketAfterTwelveHours() throws Exception {
        follows.follow(alice, bob);
        settled();
        jdbc.sql("UPDATE inbox_follow_group SET start_hour = start_hour - 11 WHERE recipient_id = :id")
                .param("id", bob.id()).update();
        jdbc.sql("UPDATE inbox_notification SET group_key = 'follow-' || (SELECT start_hour FROM inbox_follow_group WHERE recipient_id = :id) WHERE recipient_id = :id")
                .param("id", bob.id()).update();
        follows.follow(carol, bob);
        settled();
        mvc.perform(get(GROUPS).with(user(bob)))
                .andExpect(jsonPath("$.notification_groups", hasSize(1)))
                .andExpect(jsonPath("$.notification_groups[0].notifications_count").value(2));
        jdbc.sql("UPDATE inbox_follow_group SET start_hour = start_hour - 1 WHERE recipient_id = :id")
                .param("id", bob.id()).update();
        jdbc.sql("UPDATE inbox_notification SET group_key = 'follow-' || (SELECT start_hour FROM inbox_follow_group WHERE recipient_id = :id) WHERE recipient_id = :id")
                .param("id", bob.id()).update();
        follows.follow(local("inboxdave"), bob);
        settled();
        mvc.perform(get(GROUPS).with(user(bob)))
                .andExpect(jsonPath("$.notification_groups", hasSize(2)))
                .andExpect(jsonPath("$.notification_groups[0].notifications_count").value(1));
    }

    @Test
    void deletingTheLatestMessageFallsBackToThePreviousMessage() throws Exception {
        var root = createStatus(alice, "@inboxbob first", DIRECT, null);
        var latest = createStatus(alice, "@inboxbob second", DIRECT, root.id());
        settled();
        statuses.delete(latest.id(), alice.id());
        settled();
        mvc.perform(get(CONVERSATIONS).with(user(bob))).andExpect(jsonPath("$[0].last_status.id").value(root.id().toString()));
        mvc.perform(get(NOTIFICATIONS + "/unread_count").with(user(bob))).andExpect(jsonPath("$.count").value(1));
        mvc.perform(post(NOTIFICATIONS + "/clear").with(user(bob))).andExpect(content().json("{}"));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> projection.on(new StatusStored(root.id())));
        mvc.perform(get(NOTIFICATIONS + "/unread_count").with(user(bob))).andExpect(jsonPath("$.count").value(0));
        statuses.delete(root.id(), alice.id());
        settled();
        mvc.perform(get(CONVERSATIONS).with(user(bob))).andExpect(jsonPath("$", empty()));
    }

    private Actor local(String name) {
        return actors.findLocalByUsername(name).orElseGet(() -> actors.createLocalIfAbsent(name, actor -> {}).orElseThrow());
    }

    private Actor remote(String domain) {
        var uri = URI.create("https://" + domain + "/users/" + UUID.randomUUID());
        return actors.upsertRemote(new RemoteActorProfile(uri, "remote", domain, URI.create(uri + "/inbox"), null, null, null));
    }

    private URI note(Actor actor) {
        return URI.create(actor.uri() + "/notes/" + UUID.randomUUID());
    }

    private Status createStatus(Actor author, String text, dev.biribiri.elysium.content.Visibility visibility, UUID parent) {
        return statuses.create(author.id(), new NewStatus(text, null, null, visibility, false, parent));
    }

    private UUID store(Actor remote, URI uri, URI parent, Map<UUID, Boolean> mentions,
                       dev.biribiri.elysium.content.Visibility visibility) {
        return statuses.storeRemote(new RemoteStatus(uri, null, remote.id(), "<p>message</p>", null, null,
                visibility, false, parent, Instant.now(), null, mentions)).orElseThrow();
    }

    private RequestPostProcessor user(Actor actor) {
        return scoped(actor.id(), SCOPES);
    }

    private RequestPostProcessor scoped(UUID actor, Set<String> scopes) {
        return opaqueToken().principal(new TokenPrincipal("test", Optional.ofNullable(actor), scopes,
                Instant.now(), Instant.now().plusSeconds(3600)));
    }

    private <T> T values(MockHttpServletRequestBuilder request, String path) throws Exception {
        return JsonPath.read(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), path);
    }

    private void settled() {
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql("SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0);
    }
}
