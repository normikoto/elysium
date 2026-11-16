package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import dev.biribiri.elysium.TestAccounts;
import dev.biribiri.elysium.TestOAuth;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Filling in a thread from other servers. The worker doesn't run, so each test runs the queued jobs itself, and every other
 * server is a mock.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://threads.example", "elysium.jobs.worker-enabled=false"})
@AutoConfigureMockMvc
class ThreadFetchIntegrationTests {

    private static final String PUBLIC = "https://www.w3.org/ns/activitystreams#Public";
    private static final String PASSWORD = "correct horse battery staple";

    @Autowired ThreadFetches fetches;
    @Autowired StatusService statuses;
    @Autowired ActorService actors;
    @Autowired DomainBlocks blocks;
    @Autowired SchedulerClient scheduler;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired MockMvc mockMvc;
    @MockitoBean ActivityDocuments documents;

    private Actor author;

    @BeforeEach
    void setUp() throws Exception {
        doThrow(DocumentFetchException.permanent("Not found", null)).when(documents).fetch(any());
        jdbc.sql("DELETE FROM scheduled_tasks WHERE task_name = :name").param("name", ThreadFetches.TASK_NAME).update();
        author = remote();
    }

    @Test
    void fetchesMissingParentsUpToWhereTheThreadStarts() throws Exception {
        var top = noteUri(author);
        var middle = noteUri(author);
        serve(top, note(top, author, null, List.of(PUBLIC)));
        serve(middle, note(middle, author, top, List.of(PUBLIC)));
        var reply = store(author, middle);

        fetches.fetchSoon(reply);
        drain();

        var context = statuses.findContext(reply, Optional.empty()).orElseThrow();
        assertThat(context.ancestors()).extracting(Status::uri).containsExactly(top, middle);
    }

    @Test
    void stopsAtAParentThatNotEveryoneCanSee() throws Exception {
        var top = noteUri(author);
        var hidden = noteUri(author);
        serve(top, note(top, author, null, List.of(PUBLIC)));
        serve(hidden, note(hidden, author, top, List.of(author.uri() + "/followers")));
        var reply = store(author, hidden);

        fetches.fetchSoon(reply);
        drain();

        assertThat(statuses.isStored(hidden)).isFalse();
        verify(documents, times(1)).fetch(hidden);
        verify(documents, never()).fetch(top);
    }

    @Test
    void fetchesTheRepliesThatTheStatusesServerListsFromTheirOwnServers() throws Exception {
        var root = noteUri(author);
        var rootId = store(author, root, null);
        var other = remote();
        var own = noteUri(author);
        var elsewhere = noteUri(other);
        var nested = noteUri(other);
        var unrelated = noteUri(other);
        var collection = URI.create(root + "/replies");
        var nextPage = URI.create(root + "/replies?page=2");

        var rootDocument = note(root, author, null, List.of(PUBLIC));
        // The embedded copy isn't what's stored, since it isn't fetched from its own server.
        var forged = note(own, author, root, List.of(PUBLIC));
        forged.put("content", "<p>forged</p>");
        rootDocument.put("replies", Map.of("id", collection.toString(), "type", "Collection",
                "first", Map.of("type", "CollectionPage", "next", nextPage.toString(), "partOf", collection.toString(),
                        "items", List.of(forged))));
        serve(root, rootDocument);
        serve(nextPage, Map.of("id", nextPage.toString(), "type", "CollectionPage",
                "items", List.of(elsewhere.toString(), unrelated.toString())));
        serve(own, note(own, author, root, List.of(PUBLIC)));
        var elsewhereDocument = note(elsewhere, other, root, List.of(PUBLIC));
        var nestedReplies = URI.create(elsewhere + "/replies");
        elsewhereDocument.put("replies", nestedReplies.toString());
        serve(elsewhere, elsewhereDocument);
        serve(nestedReplies, Map.of("id", nestedReplies.toString(), "type", "OrderedCollection",
                "orderedItems", List.of(nested.toString())));
        serve(nested, note(nested, other, elsewhere, List.of(PUBLIC)));
        serve(unrelated, note(unrelated, other, noteUri(other), List.of(PUBLIC)));

        fetches.fetchSoon(rootId);
        drain();

        var context = statuses.findContext(rootId, Optional.empty()).orElseThrow();
        assertThat(context.descendants()).extracting(Status::uri).containsExactlyInAnyOrder(own, elsewhere, nested);
        assertThat(context.descendants()).filteredOn(found -> found.uri().equals(own))
                .extracting(Status::text).containsExactly("fetched");
        assertThat(statuses.isStored(unrelated)).isFalse();
    }

    @Test
    void neverReadsRepliesFromAnotherServerThanTheStatuses() throws Exception {
        var root = noteUri(author);
        var rootId = store(author, root, null);
        var other = remote();
        var collection = URI.create(other.uri() + "/replies");
        var rootDocument = note(root, author, null, List.of(PUBLIC));
        rootDocument.put("replies", collection.toString());
        serve(root, rootDocument);

        fetches.fetchSoon(rootId);
        drain();

        verify(documents, never()).fetch(collection);
    }

    @Test
    void neverFetchesFromABlockedServer() throws Exception {
        var blocked = remote();
        var parent = noteUri(blocked);
        serve(parent, note(parent, blocked, null, List.of(PUBLIC)));
        var reply = store(author, parent);
        blocks.create(blocked.domain(), null);

        fetches.fetchSoon(reply);
        drain();

        verify(documents, never()).fetch(parent);
    }

    @Test
    void onlyFetchesAThreadOnceInAWhile() throws Exception {
        var reply = store(author, noteUri(author));

        fetches.fetchSoon(reply);
        fetches.fetchSoon(reply);
        assertThat(jobs()).isEqualTo(2);
        drain();

        fetches.fetchSoon(reply);
        assertThat(jobs()).isZero();
    }

    @Test
    void aLaterFetchIsNotHeldUpOrCountedByAnEarlierOnesJobs() throws Exception {
        var parent = noteUri(author);
        serve(parent, note(parent, author, null, List.of(PUBLIC)));
        var reply = store(author, parent);
        fetches.fetchSoon(reply);
        // As if its jobs were still failing when the thread could be fetched again.
        jdbc.sql("UPDATE thread_refresh SET started_at = started_at - interval '16 minutes' WHERE status_id = :id")
                .param("id", reply).update();

        assertThat(fetches.fetchSoon(reply)).hasValueSatisfying(refresh -> assertThat(refresh.running()).isTrue());
        assertThat(jobs()).isEqualTo(4);
        drain();

        verify(documents, times(1)).fetch(parent);
        assertThat(fetches.find(reply)).hasValueSatisfying(refresh -> {
            assertThat(refresh.running()).isFalse();
            assertThat(refresh.resultCount()).isOne();
        });
    }

    @Test
    void viewingAThreadFetchesItOnlyForSomeoneSignedIn() throws Exception {
        var statusId = store(author, noteUri(author));
        var context = "/api/v1/statuses/" + statusId + "/context";

        mockMvc.perform(get(context)).andExpect(status().isOk());
        assertThat(jobs()).isZero();

        mockMvc.perform(get(context).header("Authorization", "Bearer " + token())).andExpect(status().isOk());
        assertThat(jobs()).isEqualTo(2);
    }

    @Test
    void tellsClientsWhileTheThreadIsFetchedAndWhatItFound() throws Exception {
        var parent = noteUri(author);
        serve(parent, note(parent, author, null, List.of(PUBLIC)));
        var statusId = store(author, parent);
        var token = token();
        var refresh = "/api/v1_alpha/async_refreshes/context-" + statusId;

        mockMvc.perform(get(refresh).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/statuses/" + statusId + "/context").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Mastodon-Async-Refresh",
                        "id=\"context-" + statusId + "\", retry=5, result_count=0"));
        mockMvc.perform(get(refresh).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.async_refresh.id").value("context-" + statusId))
                .andExpect(jsonPath("$.async_refresh.status").value("running"));

        drain();

        mockMvc.perform(get(refresh).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.async_refresh.status").value("finished"))
                .andExpect(jsonPath("$.async_refresh.result_count").value(1));
        // Finished, and not started again so soon, so there's nothing to check on.
        mockMvc.perform(get("/api/v1/statuses/" + statusId + "/context").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Mastodon-Async-Refresh"));
        mockMvc.perform(get(refresh)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1_alpha/async_refreshes/" + statusId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /**
     * Runs every queued job, and each step it goes on to, until nothing is left.
     */
    private void drain() {
        for (var round = 0; round < 20; round++) {
            var queued = scheduler.getScheduledExecutionsForTask(ThreadFetches.TASK_NAME, ThreadFetchJob.class);
            if (queued.isEmpty()) {
                return;
            }
            for (var execution : queued) {
                scheduler.cancel(execution.getTaskInstance());
                var job = execution.getData();
                while (job != null) {
                    job = runOnce(job);
                }
            }
        }
        throw new AssertionError("Jobs kept being queued");
    }

    /**
     * @return what the job goes on to, or null if it's done
     */
    @SuppressWarnings("unchecked")
    private ThreadFetchJob runOnce(ThreadFetchJob job) {
        var completion = fetches.run(job);
        ExecutionComplete complete = mock();
        ExecutionOperations<ThreadFetchJob> operations = mock();
        completion.complete(complete, operations);
        return Mockito.mockingDetails(operations).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("reschedule") && call.getArguments().length == 3)
                .map(call -> (ThreadFetchJob) call.getArguments()[2])
                .findFirst()
                .orElse(null);
    }

    private long jobs() {
        return jdbc.sql("SELECT count(*) FROM scheduled_tasks WHERE task_name = :name")
                .param("name", ThreadFetches.TASK_NAME).query(Long.class).single();
    }

    private String token() throws Exception {
        TestAccounts.makeSureAnAccountExists(actors);
        var username = "thread" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/signup").with(csrf())
                        .param("invite", TestAccounts.invite(jdbc))
                        .param("username", username)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(status().is3xxRedirection());
        return TestOAuth.userToken(mockMvc, username, PASSWORD, "read");
    }

    private Actor remote() {
        var host = "thread" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/someone");
        return actors.upsertRemote(new RemoteActorProfile(uri, "someone", host, URI.create(uri + "/inbox"), null, null,
                null, URI.create(uri + "/followers")));
    }

    private static URI noteUri(Actor author) {
        return URI.create(author.uri() + "/statuses/" + UUID.randomUUID());
    }

    /**
     * Stores a public reply to a status that isn't stored.
     */
    private UUID store(Actor author, URI inReplyTo) {
        return store(author, noteUri(author), inReplyTo);
    }

    private UUID store(Actor author, URI uri, URI inReplyTo) {
        return statuses.storeRemote(new RemoteStatus(uri, null, author.id(), "<p>stored</p>", null, null,
                Visibility.PUBLIC, false, inReplyTo, Instant.now())).orElseThrow();
    }

    private static Map<String, Object> note(URI id, Actor author, URI inReplyTo, List<String> to) {
        var note = new LinkedHashMap<String, Object>();
        note.put("@context", "https://www.w3.org/ns/activitystreams");
        note.put("id", id.toString());
        note.put("type", "Note");
        note.put("attributedTo", author.uri().toString());
        note.put("content", "<p>fetched</p>");
        note.put("published", Instant.now().minusSeconds(60).toString());
        note.put("to", to);
        if (inReplyTo != null) {
            note.put("inReplyTo", inReplyTo.toString());
        }
        return note;
    }

    private void serve(URI uri, Map<String, Object> document) throws DocumentFetchException {
        doReturn(json.readTree(json.writeValueAsString(document))).when(documents).fetch(uri);
    }
}
