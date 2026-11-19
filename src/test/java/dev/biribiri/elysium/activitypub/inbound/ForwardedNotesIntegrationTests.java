package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.activitypub.signature.VerifiedSignature;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A forwarded {@code Create} that its origin doesn't serve, whose note is fetched instead. As in
 * {@link ForwardedActivitiesIntegrationTests}, each step is called directly with no worker running, and other servers are
 * a mock.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://forwarded-notes.example",
        "elysium.jobs.worker-enabled=false"
})
class ForwardedNotesIntegrationTests {

    @Autowired
    private Inbox inbox;
    @Autowired
    private ForwardedActivities forwarded;
    @Autowired
    private ForwardedNotes notes;
    @Autowired
    private InboundActivities inbound;
    @Autowired
    private StoredActivities stored;
    @Autowired
    private CreateHandler createHandler;
    @Autowired
    private UpdateHandler updateHandler;
    @Autowired
    private DomainBlocks blocks;
    @Autowired
    private ActorService actors;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;
    @MockitoBean
    private ActivityDocuments documents;
    @MockitoSpyBean
    private TargetFetchTurns turns;

    private VerifiedSignature forwarder;
    private String origin;
    private String actor;
    private String id;
    private String note;
    private Actor recipient;

    @BeforeEach
    void setUp() throws Exception {
        var forwarderUri = "https://f" + UUID.randomUUID() + ".test/users/f";
        forwarder = new VerifiedSignature(forwarderUri + "#main-key", URI.create(forwarderUri));
        origin = "o" + UUID.randomUUID() + ".test";
        actor = "https://" + origin + "/users/a";
        id = "https://" + origin + "/activities/" + UUID.randomUUID();
        note = "https://" + origin + "/notes/" + UUID.randomUUID();
        recipient = local();
        actors.upsertRemote(new RemoteActorProfile(URI.create(actor), "a", origin, URI.create(actor + "/inbox"),
                null, null, null, URI.create(actor + "/followers")));
        when(documents.fetch(URI.create(actor))).thenReturn(json.readTree(
                "{\"id\":\"%s\",\"type\":\"Person\",\"preferredUsername\":\"a\",\"inbox\":\"%s/inbox\"}"
                        .formatted(actor, actor)));
        when(documents.fetch(URI.create(id))).thenThrow(DocumentFetchException.gone("not served"));
    }

    @Test
    void queuesAFetchOfTheNoteWhenTheOriginDoesNotServeTheCreate() {
        receive(create(id, note, recipient.uri().toString()));

        forwarded.fetch(forwardedJob());

        assertThat(noteJobs()).isOne();
    }

    @Test
    void storesTheNoteItsServerServes() throws Exception {
        serveNote(noteDocument(note, actor, recipient.uri().toString(), Instant.now()));

        storeNote();

        assertThat(statusCount(note)).isOne();
    }

    @Test
    void decidesWhoTheNoteIsForFromWhatItsServerServes() throws Exception {
        // The forwarded copy names someone here, but the note's server doesn't.
        receive(create(id, note, recipient.uri().toString()));
        serveNote(noteDocument(note, actor, "https://elsewhere.test/users/x", Instant.now()));

        forwarded.fetch(forwardedJob());
        storeNote();

        assertThat(statusCount(note)).isZero();
    }

    @Test
    void storesNothingUnderTheForwardedId() throws Exception {
        serveNote(noteDocument(note, actor, recipient.uri().toString(), Instant.now()));

        storeNote();

        assertThat(stored.exists(id)).isFalse();
        var real = "{\"id\":\"%s\",\"type\":\"Create\",\"actor\":\"%s\",\"object\":\"%s\"}".formatted(id, actor, note);
        assertThat(inbound.accept(new InboundActivity(id, actor, "Create", real))).isTrue();
    }

    @Test
    void storesForwardsOfTheSameNoteOnce() throws Exception {
        serveNote(noteDocument(note, actor, recipient.uri().toString(), Instant.now()));

        notes.run(noteJob(now()));
        notes.run(noteJob(now()));

        verify(documents, times(1)).fetch(URI.create(note));
        assertThat(stored.exists(createId())).isTrue();
    }

    @Test
    void storesTheCreateAsArrivingWhenTheForwardedCopyDid() throws Exception {
        var arrivedAt = now().minusSeconds(60);
        serveNote(noteDocument(note, actor, recipient.uri().toString(), Instant.now()));

        notes.run(noteJob(arrivedAt));

        assertThat(stored.find(createId()).orElseThrow().firstReceivedAt()).isEqualTo(arrivedAt);
    }

    @Test
    void dropsANoteAttributedToSomeoneElse() throws Exception {
        serveNote(noteDocument(note, "https://" + origin + "/users/b", recipient.uri().toString(), Instant.now()));

        notes.run(noteJob(now()));

        assertThat(stored.exists(createId())).isFalse();
    }

    @Test
    void dropsWhatItsServerServesUnderAnotherId() throws Exception {
        serveNote(noteDocument(note + "/other", actor, recipient.uri().toString(), Instant.now()));

        notes.run(noteJob(now()));

        assertThat(stored.exists(createId())).isFalse();
    }

    @Test
    void dropsAnOldNote() throws Exception {
        var published = now().minus(ForwardedNotes.NEW_FOR).minusSeconds(60);
        serveNote(noteDocument(note, actor, recipient.uri().toString(), published));

        notes.run(noteJob(now()));

        assertThat(stored.exists(createId())).isFalse();
    }

    @Test
    void dropsANoteThatDoesNotSayWhenItWasPublished() throws Exception {
        serveNote(noteDocument(note, actor, recipient.uri().toString(), null));

        notes.run(noteJob(now()));

        assertThat(stored.exists(createId())).isFalse();
    }

    @Test
    void dropsANoteOfASuspendedActor() throws Exception {
        serveNote(noteDocument(note, actor, recipient.uri().toString(), Instant.now()));
        actors.suspend(actors.findByUri(URI.create(actor)).orElseThrow().id());

        notes.run(noteJob(now()));

        assertThat(stored.exists(createId())).isFalse();
    }

    @Test
    void doesNotFetchFromAServerBlockedSinceItWasQueued() throws Exception {
        blocks.create(origin, null);

        notes.run(noteJob(now()));

        verify(documents, never()).fetch(URI.create(note));
    }

    @Test
    void dropsANoteThatIsGone() throws Exception {
        when(documents.fetch(URI.create(note))).thenThrow(DocumentFetchException.gone("gone"));

        notes.run(noteJob(now()));

        assertThat(stored.exists(createId())).isFalse();
    }

    @Test
    void triesAgainWhenTheNotesServerCannotBeReached() throws Exception {
        when(documents.fetch(URI.create(note))).thenThrow(DocumentFetchException.temporary("down", null));

        assertThatThrownBy(() -> notes.run(noteJob(now()))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void waitsForItsTurnWithoutFetching() throws Exception {
        var later = now().plusSeconds(30);
        doReturn(Optional.of(later)).when(turns).take(any(), any(), any());
        var job = noteJob(now());

        var handler = notes.run(job);

        verify(documents, never()).fetch(URI.create(note));
        ExecutionComplete complete = mock();
        ExecutionOperations<ForwardedNoteJob> operations = mock();
        handler.complete(complete, operations);
        verify(operations).reschedule(complete, later, job.withTurn(later));
    }

    @Test
    void dropsAForwardWhoseTurnIsTooFarAway() throws Exception {
        doReturn(Optional.empty()).when(turns).take(any(), any(), any());

        notes.run(noteJob(now()));

        verify(documents, never()).fetch(URI.create(note));
    }

    @Test
    void dropsAForwardWhoseActorIsTooLongToStoreUnder() throws Exception {
        var longActor = "https://" + origin + "/users/" + "a".repeat(InboundActivity.MAX_ID_BYTES);

        notes.run(new ForwardedNoteJob(note, longActor, "Create", now(), null));

        verify(documents, never()).fetch(URI.create(note));
    }

    @Test
    void doesNotFetchANoteOnAnotherServer() {
        var elsewhere = "https://elsewhere.test/notes/" + UUID.randomUUID();
        receive(create(id, elsewhere, recipient.uri().toString()));

        forwarded.fetch(forwardedJob());

        assertThat(jobs("forwarded-note", elsewhere)).isZero();
    }

    @Test
    void queuesAFetchOfTheNoteWhenTheOriginDoesNotServeTheUpdate() {
        receive(update(id, note));

        forwarded.fetch(forwardedJob());

        assertThat(noteJobs()).isOne();
        assertThat(jdbc.sql("""
                        SELECT convert_from(task_data, 'UTF8') FROM scheduled_tasks
                        WHERE task_name = 'forwarded-note' AND convert_from(task_data, 'UTF8') LIKE :note
                        """)
                .param("note", "%" + note + "%")
                .query(String.class)
                .single()).contains("\"type\":\"Update\"");
    }

    /**
     * The second can arrive while the first's fetch is running, after it got the first edit.
     */
    @Test
    void queuesAFetchForEachForwardedUpdateOfTheSameNote() throws Exception {
        var second = "https://" + origin + "/activities/" + UUID.randomUUID();
        when(documents.fetch(URI.create(second))).thenThrow(DocumentFetchException.gone("not served"));
        receive(update(id, note));
        receive(update(second, note));

        forwarded.fetch(forwardedJob());
        forwarded.fetch(forwardedJob(second));

        assertThat(noteJobs()).isEqualTo(2);
    }

    @Test
    void doesNotQueueAFetchForAnUpdateOfTheActor() {
        receive(update(id, actor));

        forwarded.fetch(forwardedJob());

        assertThat(jobs("forwarded-note", actor)).isZero();
    }

    @Test
    void editsWithWhatTheNotesServerServesForAForwardedUpdate() throws Exception {
        var published = now().minusSeconds(600);
        serveNote(noteDocument(note, actor, recipient.uri().toString(), published));
        storeNote();
        serveNote(editedNote(published, published.plusSeconds(60), "Edited"));

        updateNote();

        assertThat(content(note)).contains("Edited");
    }

    @Test
    void storesANoteThatIsNotStoredYetForAForwardedUpdate() throws Exception {
        var published = now().minusSeconds(600);
        serveNote(editedNote(published, published.plusSeconds(60), "Edited"));

        updateNote();

        assertThat(content(note)).contains("Edited");
    }

    /**
     * Unlike a {@code Create}, an {@code Update} of an old note isn't dropped, since it's stored in its place by date. It's
     * public, because an old direct note isn't stored.
     */
    @Test
    void storesAnOldNoteForAForwardedUpdate() throws Exception {
        var published = now().minus(ForwardedNotes.NEW_FOR).minusSeconds(600);
        serveNote(editedNote(published, published.plusSeconds(60), "Edited"));

        updateNote();

        assertThat(statusCount(note)).isOne();
    }

    @Test
    void storesEachVersionServedForAForwardedUpdateOnce() throws Exception {
        var published = now().minusSeconds(600);
        serveNote(editedNote(published, published.plusSeconds(60), "First"));
        notes.run(updateJob());
        notes.run(updateJob());
        serveNote(editedNote(published, published.plusSeconds(120), "Second"));
        notes.run(updateJob());

        assertThat(storedUpdates()).hasSize(2);
    }

    /**
     * The first time can have been before the note was stored, when some of it, such as a poll's counts, couldn't be
     * applied.
     */
    @Test
    void appliesAVersionAgainWhenItIsFetchedAgain() throws Exception {
        var published = now().minusSeconds(600);
        serveNote(editedNote(published, published.plusSeconds(60), "Edited"));
        var first = now().minusSeconds(60);

        notes.run(new ForwardedNoteJob(note, actor, "Update", first, null));
        notes.run(new ForwardedNoteJob(note, actor, "Update", first.plusSeconds(30), null));

        assertThat(storedUpdates()).hasSize(1);
        assertThat(jobs("inbound-activity", storedUpdates().getFirst())).isEqualTo(2);
    }

    @Test
    void doesNotStoreAnUpdateUnderTheForwardedId() throws Exception {
        var published = now().minusSeconds(600);
        serveNote(editedNote(published, published.plusSeconds(60), "Edited"));

        notes.run(updateJob());

        assertThat(stored.exists(id)).isFalse();
        assertThat(storedUpdates()).hasSize(1);
    }

    @Test
    void dropsAnUpdatedNoteAttributedToSomeoneElse() throws Exception {
        serveNote(noteDocument(note, "https://" + origin + "/users/b", recipient.uri().toString(), Instant.now()));

        notes.run(updateJob());

        assertThat(storedUpdates()).isEmpty();
    }

    /**
     * Runs the {@code Update} job, and handles what it stored.
     */
    private void updateNote() {
        notes.run(updateJob());
        storedUpdates().forEach(updateId -> updateHandler.handle(stored.find(updateId).orElseThrow()));
    }

    private List<String> storedUpdates() {
        return jdbc.sql("SELECT activity_id FROM inbound_activity WHERE activity_id LIKE :prefix")
                .param("prefix", actor + "#forwarded-update/%")
                .query(String.class)
                .list();
    }

    private ForwardedNoteJob updateJob() {
        return new ForwardedNoteJob(note, actor, "Update", now(), null);
    }

    private String editedNote(Instant published, Instant updated, String content) {
        return """
                {"id":"%s","type":"Note","attributedTo":"%s","to":["https://www.w3.org/ns/activitystreams#Public"],
                 "cc":["%s"],"content":"<p>%s</p>","published":"%s","updated":"%s"}"""
                .formatted(note, actor, recipient.uri(), content, published, updated);
    }

    private static String update(String id, String object) {
        return """
                {"id":"%s","type":"Update","actor":"%s",
                 "object":{"id":"%s","type":"Note","attributedTo":"%s","content":"<p>Forwarded</p>"}}"""
                .formatted(id, actorOf(id), object, actorOf(id));
    }

    private String content(String uri) {
        return jdbc.sql("SELECT content FROM status WHERE uri = :uri").param("uri", uri).query(String.class).single();
    }

    private void storeNote() {
        notes.run(noteJob(now()));
        createHandler.handle(stored.find(createId()).orElseThrow());
    }

    private Inbox.Result receive(String body) {
        return inbox.receive(forwarder, body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The job that the inbox queued for the forwarded copy.
     */
    private ForwardedJob forwardedJob() {
        return forwardedJob(id);
    }

    private ForwardedJob forwardedJob(String id) {
        var data = jdbc.sql("""
                        SELECT convert_from(task_data, 'UTF8') FROM scheduled_tasks
                        WHERE task_name = 'forwarded-activity' AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("id", "%" + id + "%")
                .query(String.class)
                .single();
        return json.readValue(data, ForwardedJob.class);
    }

    private ForwardedNoteJob noteJob(Instant queuedAt) {
        return new ForwardedNoteJob(note, actor, "Create", queuedAt, null);
    }

    private String createId() {
        return ForwardedNotes.activityId("Create", actor, note);
    }

    private void serveNote(String document) throws Exception {
        when(documents.fetch(URI.create(note))).thenReturn(json.readTree(document));
    }

    private static String create(String id, String note, String to) {
        return """
                {"id":"%s","type":"Create","actor":"%s","to":["%s"],
                 "object":{"id":"%s","type":"Note","attributedTo":"%s","to":["%s"],"content":"<p>Forwarded</p>"}}"""
                .formatted(id, actorOf(id), to, note, actorOf(id), to);
    }

    private static String actorOf(String id) {
        return "https://" + URI.create(id).getHost() + "/users/a";
    }

    private static String noteDocument(String id, String attributedTo, String to, Instant published) {
        var publishedProperty = published == null ? "" : ",\"published\":\"" + published + "\"";
        return "{\"id\":\"%s\",\"type\":\"Note\",\"attributedTo\":\"%s\",\"to\":[\"%s\"],\"content\":\"<p>Origin</p>\"%s}"
                .formatted(id, attributedTo, to, publishedProperty);
    }

    private long statusCount(String uri) {
        return jdbc.sql("SELECT count(*) FROM status WHERE uri = :uri").param("uri", uri).query(Long.class).single();
    }

    private long noteJobs() {
        return jobs("forwarded-note", note);
    }

    private long jobs(String task, String key) {
        return jdbc.sql("""
                        SELECT count(*) FROM scheduled_tasks
                        WHERE task_name = :task AND convert_from(task_data, 'UTF8') LIKE :key
                        """)
                .param("task", task)
                .param("key", "%" + key + "%")
                .query(Long.class)
                .single();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private Actor local() {
        var name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return actors.createLocalIfAbsent(name, created -> {
        }).orElseThrow();
    }
}
