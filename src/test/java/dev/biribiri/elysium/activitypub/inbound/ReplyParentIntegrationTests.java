package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
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
import static org.mockito.Mockito.verify;

/**
 * A public reply that's addressed to nobody here, and arrives before its parent. The worker doesn't run, so each test runs
 * the queued jobs itself, and every other server is a mock.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://parents.example", "elysium.jobs.worker-enabled=false"})
class ReplyParentIntegrationTests {

    private static final String PUBLIC = "https://www.w3.org/ns/activitystreams#Public";

    @Autowired CreateHandler creates;
    @Autowired UpdateHandler updates;
    @Autowired ReplyParentChecks checks;
    @Autowired InboundActivities inbound;
    @Autowired ActorService actors;
    @Autowired SchedulerClient scheduler;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @MockitoBean ActivityDocuments documents;

    private Actor follower;
    private Actor followed;
    private Actor replier;

    @BeforeEach
    void setUp() throws Exception {
        doThrow(DocumentFetchException.permanent("Not found", null)).when(documents).fetch(any());
        jdbc.sql("DELETE FROM scheduled_tasks WHERE task_name = :name").param("name", ReplyParentChecks.TASK.getTaskName())
                .update();
        follower = actors.createLocalIfAbsent("p" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                actor -> {
                }).orElseThrow();
        followed = remote();
        replier = remote();
        follow(follower, followed);
    }

    @Test
    void keepsTheReplyOnceItsParentsCreateArrives() throws Exception {
        var parent = noteUri(followed);
        var reply = noteUri(replier);

        handle(creates, activity("Create", replier, note(reply, replier, parent)));
        assertThat(status(reply)).isEmpty();
        assertThat(jobs()).hasSize(1);
        creates.handle(activity("Create", followed, note(parent, followed, null)));
        drain();

        var stored = status(reply).orElseThrow();
        assertThat(stored.get("in_reply_to_id")).isEqualTo(status(parent).orElseThrow().get("id"));
        verify(documents, never()).fetch(parent);
    }

    @Test
    void fetchesAParentThatNeverArrivesWhenItsAuthorIsFollowed() throws Exception {
        var parent = noteUri(followed);
        var reply = noteUri(replier);
        serve(parent, note(parent, followed, null));

        handle(creates, activity("Create", replier, note(reply, replier, parent)));
        drain();

        assertThat(status(parent)).isPresent();
        assertThat(status(reply).orElseThrow().get("in_reply_to_account_id")).isEqualTo(followed.id());
    }

    @Test
    void keepsNeitherWhenTheParentsAuthorIsntFollowedHere() throws Exception {
        var stranger = remote();
        var byStranger = noteUri(stranger);
        var unknownAuthor = URI.create("https://" + stranger.domain() + "/users/unknown");
        var byUnknown = URI.create(unknownAuthor + "/statuses/" + UUID.randomUUID());
        var first = noteUri(replier);
        var second = noteUri(replier);
        serve(byStranger, note(byStranger, stranger, null));
        var unknownNote = note(byUnknown, stranger, null);
        unknownNote.put("attributedTo", unknownAuthor.toString());
        serve(byUnknown, unknownNote);

        handle(creates, activity("Create", replier, note(first, replier, byStranger)));
        handle(creates, activity("Create", replier, note(second, replier, byUnknown)));
        drain();

        assertThat(status(byStranger)).isEmpty();
        assertThat(status(first)).isEmpty();
        assertThat(status(byUnknown)).isEmpty();
        assertThat(status(second)).isEmpty();
        verify(documents, never()).fetch(unknownAuthor);
    }

    @Test
    void doesNotWaitForTheParentOfAReplyThatOnlyFollowersCanSee() {
        var parent = noteUri(followed);
        var reply = note(noteUri(replier), replier, parent);
        reply.put("to", List.of(replier.uri() + "/followers"));

        handle(creates, activity("Create", replier, reply));

        assertThat(jobs()).isEmpty();
    }

    @Test
    void dropsTheReplyWhenItsParentCantBeFetched() throws Exception {
        var parent = noteUri(followed);
        var reply = noteUri(replier);

        handle(creates, activity("Create", replier, note(reply, replier, parent)));
        drain();

        verify(documents).fetch(parent);
        assertThat(status(reply)).isEmpty();
        assertThat(jobs()).isEmpty();
    }

    @Test
    void anUpdateOfAReplyThatIsntStoredWaitsForItsParentToo() throws Exception {
        var parent = noteUri(followed);
        var reply = noteUri(replier);

        var edited = note(reply, replier, parent);
        edited.put("updated", Instant.now().minusSeconds(30).toString());
        handle(updates, activity("Update", replier, edited));
        assertThat(status(reply)).isEmpty();
        creates.handle(activity("Create", followed, note(parent, followed, null)));
        drain();

        assertThat(status(reply)).isPresent();
    }

    // Helpers

    private void handle(InboundActivityHandler handler, InboundActivity activity) {
        inbound.accept(activity);
        handler.handle(activity);
    }

    /**
     * Runs every queued job, and each step it goes on to, until nothing is left.
     */
    private void drain() {
        for (var execution : scheduler.getScheduledExecutionsForTask(ReplyParentChecks.TASK.getTaskName(),
                ReplyParentJob.class)) {
            scheduler.cancel(execution.getTaskInstance());
            var job = execution.getData();
            while (job != null) {
                job = runOnce(job);
            }
        }
    }

    /**
     * @return what the job goes on to, or null if it's done
     */
    @SuppressWarnings("unchecked")
    private ReplyParentJob runOnce(ReplyParentJob job) {
        var completion = checks.run(job);
        ExecutionComplete complete = mock();
        ExecutionOperations<ReplyParentJob> operations = mock();
        completion.complete(complete, operations);
        return Mockito.mockingDetails(operations).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("reschedule") && call.getArguments().length == 3)
                .map(call -> (ReplyParentJob) call.getArguments()[2])
                .findFirst()
                .orElse(null);
    }

    private List<ReplyParentJob> jobs() {
        return scheduler.getScheduledExecutionsForTask(ReplyParentChecks.TASK.getTaskName(), ReplyParentJob.class)
                .stream().map(execution -> execution.getData()).toList();
    }

    private InboundActivity activity(String type, Actor actor, Map<String, Object> note) {
        var id = note.get("id") + "/" + type.toLowerCase();
        var body = "{\"id\":\"%s\",\"type\":\"%s\",\"actor\":\"%s\",\"object\":%s}"
                .formatted(id, type, actor.uri(), json.writeValueAsString(note));
        return new InboundActivity(id, actor.uri().toString(), type, body);
    }

    private static Map<String, Object> note(URI id, Actor author, URI inReplyTo) {
        var note = new HashMap<String, Object>();
        note.put("id", id.toString());
        note.put("type", "Note");
        note.put("attributedTo", author.uri().toString());
        note.put("content", "<p>Hi</p>");
        note.put("published", Instant.now().minusSeconds(60).toString());
        note.put("to", List.of(PUBLIC));
        note.put("cc", List.of(author.uri() + "/followers"));
        if (inReplyTo != null) {
            note.put("inReplyTo", inReplyTo.toString());
        }
        return note;
    }

    private void serve(URI uri, Map<String, Object> document) throws DocumentFetchException {
        doReturn(json.readTree(json.writeValueAsString(document))).when(documents).fetch(uri);
    }

    private Actor remote() {
        var host = "p" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/someone");
        return actors.upsertRemote(new RemoteActorProfile(uri, "someone", host, URI.create(uri + "/inbox"), null, null,
                null, URI.create(uri + "/followers")));
    }

    private static URI noteUri(Actor author) {
        return URI.create(author.uri() + "/statuses/" + UUID.randomUUID());
    }

    private Optional<Map<String, Object>> status(URI uri) {
        return jdbc.sql("SELECT * FROM status WHERE uri = :uri").param("uri", uri.toString())
                .query().listOfRows().stream().findFirst();
    }

    private void follow(Actor from, Actor to) {
        jdbc.sql("""
                        INSERT INTO follow (id, follower_id, followed_id, state, activity_uri)
                        VALUES (:id, :follower, :followed, 'accepted', :activity)
                        """)
                .param("id", UUID.randomUUID())
                .param("follower", from.id())
                .param("followed", to.id())
                .param("activity", from.uri() + "#follows/" + UUID.randomUUID())
                .update();
    }
}
