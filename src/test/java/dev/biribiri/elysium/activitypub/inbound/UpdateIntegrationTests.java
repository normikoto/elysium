package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Calls the handlers directly with no worker running, as {@link CreateIntegrationTests} does. It has the same configuration,
 * so that the two share a context, which keeps the test run within its memory, and each test uses a host of its own.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://create.example", "elysium.jobs.worker-enabled=false"})
class UpdateIntegrationTests {

    private static final String PUBLIC = "https://www.w3.org/ns/activitystreams#Public";

    @Autowired
    private UpdateHandler handler;
    @Autowired
    private CreateHandler creates;
    @Autowired
    private ActorService actors;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private DomainBlocks blocks;
    @Autowired
    private StatusService statuses;
    @MockitoBean
    private ActivityDocuments documents;

    private Actor author;
    private Actor neighbour;
    private String host;
    private String followers;

    @BeforeEach
    void setUp() {
        var follower = actors.createLocalIfAbsent("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), actor -> {
        }).orElseThrow();
        host = "h" + UUID.randomUUID() + ".test";
        author = remote("s");
        neighbour = remote("t");
        followers = author.uri() + "/followers";
        follow(follower, author);
        follow(follower, neighbour);
    }

    // Statuses

    @Test
    void replacesWhatAnEditCanChange() {
        var noteId = stored();
        var edited = new HashMap<>(note(noteId, "2026-02-01T00:00:00Z"));
        edited.put("content", "<p>Edited <script>alert(1)</script><b>text</b></p>");
        edited.put("summary", "<p>Spoiler</p>");
        edited.put("contentMap", Map.of("fr", "<p>Modifié</p>"));
        // None of these can change in an edit.
        edited.put("to", List.of(followers));
        edited.put("cc", List.of());
        edited.put("inReplyTo", "https://" + host + "/notes/parent");
        edited.put("published", "2026-01-15T00:00:00Z");
        edited.put("url", "https://" + host + "/@s/other");

        handler.handle(update(edited));

        var row = status(noteId).orElseThrow();
        assertThat(row.get("content")).isEqualTo("<p>Edited <b>text</b></p>");
        assertThat(row.get("text")).isEqualTo("Edited text");
        assertThat(row.get("spoiler_text")).isEqualTo("Spoiler");
        assertThat(row.get("sensitive")).isEqualTo(true);
        assertThat(row.get("language")).isEqualTo("fr");
        assertThat(((Timestamp) row.get("edited_at")).toInstant()).hasToString("2026-02-01T00:00:00Z");
        assertThat(row.get("visibility")).isEqualTo("public");
        assertThat(row.get("reply")).isEqualTo(false);
        assertThat(row.get("in_reply_to_uri")).isNull();
        assertThat(((Timestamp) row.get("published_at")).toInstant()).hasToString("2026-01-01T00:00:00Z");
        assertThat(row.get("url")).isNull();
    }

    @Test
    void ignoresAnOlderOrRepeatedEdit() {
        var noteId = stored();
        handler.handle(update(edit(noteId, "2026-03-01T00:00:00Z", "<p>Newest</p>")));

        handler.handle(update(edit(noteId, "2026-02-01T00:00:00Z", "<p>Older</p>")));
        handler.handle(update(edit(noteId, "2026-03-01T00:00:00Z", "<p>Same time</p>")));

        assertThat(status(noteId).orElseThrow().get("content")).isEqualTo("<p>Newest</p>");
    }

    @Test
    void ignoresAnEditThatDoesNotSayWhenItWasMade() {
        var noteId = stored();
        var edited = new HashMap<>(note(noteId, null));
        edited.put("content", "<p>Undated</p>");

        handler.handle(update(edited));

        var row = status(noteId).orElseThrow();
        assertThat(row.get("content")).isEqualTo("<p>Hi</p>");
        assertThat(row.get("edited_at")).isNull();
    }

    @Test
    void anUpdatedThatIsntAfterPublishedIsntAnEdit() {
        var noteId = stored();

        handler.handle(update(edit(noteId, "2026-01-01T00:00:00Z", "<p>Same time</p>")));
        handler.handle(update(edit(noteId, "2025-12-31T00:00:00Z", "<p>Earlier</p>")));

        var row = status(noteId).orElseThrow();
        assertThat(row.get("content")).isEqualTo("<p>Hi</p>");
        assertThat(row.get("edited_at")).isNull();
    }

    @Test
    void anUpdatedInTheFutureIsIgnored() {
        var noteId = stored();
        var future = Instant.now().plus(Duration.ofDays(1)).toString();

        handler.handle(update(edit(noteId, future, "<p>Future</p>")));
        handler.handle(update(edit(noteId, Instant.now().toString(), "<p>Now</p>")));

        assertThat(status(noteId).orElseThrow().get("content")).isEqualTo("<p>Now</p>");
    }

    @Test
    void aCreateWithAnUpdatedInTheFutureIsntStoredAsEdited() {
        var noteId = noteId();

        creates.handle(create(note(noteId, Instant.now().plus(Duration.ofDays(1)).toString())));

        assertThat(status(noteId).orElseThrow().get("edited_at")).isNull();
    }

    @Test
    void anEditOlderThanTheStoredVersionIsIgnored() {
        var noteId = noteId();
        creates.handle(create(edit(noteId, "2026-03-01T00:00:00Z", "<p>Already edited</p>")));

        handler.handle(update(edit(noteId, "2026-02-01T00:00:00Z", "<p>Older</p>")));

        var row = status(noteId).orElseThrow();
        assertThat(row.get("content")).isEqualTo("<p>Already edited</p>");
        assertThat(((Timestamp) row.get("edited_at")).toInstant()).hasToString("2026-03-01T00:00:00Z");
    }

    @Test
    void aCreateWhoseUpdatedIsItsPublishedIsNotAnEdit() {
        var noteId = noteId();
        var note = new HashMap<>(note(noteId, "2026-01-01T00:00:00Z"));

        creates.handle(create(note));

        assertThat(status(noteId).orElseThrow().get("edited_at")).isNull();
    }

    // Updates that run before their Create

    @Test
    void storesARecentNoteThatIsntStoredYet() {
        var noteId = noteId();
        var published = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        var updated = published.plus(Duration.ofMinutes(5));
        var original = new HashMap<>(note(noteId, null));
        original.put("published", published.toString());
        var edited = new HashMap<>(original);
        edited.put("updated", updated.toString());
        edited.put("content", "<p>Edited</p>");

        handler.handle(update(edited));
        creates.handle(create(original));

        var row = status(noteId).orElseThrow();
        assertThat(row.get("content")).isEqualTo("<p>Edited</p>");
        assertThat(row.get("visibility")).isEqualTo("public");
        assertThat(((Timestamp) row.get("edited_at")).toInstant()).isEqualTo(updated);
        assertThat(((Timestamp) row.get("published_at")).toInstant()).isEqualTo(published);
    }

    @Test
    void ignoresAnOldNoteThatIsntStored() {
        var noteId = noteId();
        var old = new HashMap<>(edit(noteId, Instant.now().toString(), "<p>Edited</p>"));
        old.put("published", Instant.now().minus(Duration.ofDays(4)).toString());

        handler.handle(update(old));

        assertThat(status(noteId)).isEmpty();
    }

    @Test
    void ignoresANoteThatIsntStoredAndDoesNotSayWhenItWasPublished() {
        var noteId = noteId();
        var undated = new HashMap<>(edit(noteId, Instant.now().toString(), "<p>Edited</p>"));
        undated.remove("published");

        handler.handle(update(undated));

        assertThat(status(noteId)).isEmpty();
    }

    @Test
    void aLateUpdateHasTheSameChecksAsACreate() {
        var stranger = remote("stranger");
        var notForAnyone = "https://" + host + "/notes/" + UUID.randomUUID();
        var note = new HashMap<>(recent(notForAnyone));
        note.put("attributedTo", stranger.uri().toString());
        note.put("cc", List.of(stranger.uri() + "/followers"));
        var direct = noteId();
        var directNote = new HashMap<>(recent(direct));
        directNote.put("to", List.of());
        directNote.put("cc", List.of());

        handler.handle(updateOf(notForAnyone + "#updates/1", stranger.uri(), json.writeValueAsString(note)));
        handler.handle(update(directNote));

        assertThat(status(notForAnyone)).isEmpty();
        assertThat(status(direct)).isEmpty();
    }

    @Test
    void aDeletedNoteIsntStoredByALateUpdate() {
        var noteId = noteId();
        statuses.deleteRemote(URI.create(noteId), author.uri());

        handler.handle(update(recent(noteId)));

        assertThat(status(noteId)).isEmpty();
    }

    @Test
    void cannotEditAnotherActorsStatus() {
        var noteId = stored();
        var edited = new HashMap<>(edit(noteId, "2026-02-01T00:00:00Z", "<p>Not yours</p>"));
        edited.put("attributedTo", neighbour.uri().toString());

        handler.handle(updateOf(noteId + "#updates/1", neighbour.uri(), json.writeValueAsString(edited)));

        assertThat(status(noteId).orElseThrow().get("content")).isEqualTo("<p>Hi</p>");
    }

    @Test
    void dropsANoteAttributedToSomeoneElse() {
        var noteId = stored();
        var edited = new HashMap<>(edit(noteId, "2026-02-01T00:00:00Z", "<p>Edited</p>"));
        edited.put("attributedTo", neighbour.uri().toString());

        handler.handle(update(edited));

        assertThat(status(noteId).orElseThrow().get("content")).isEqualTo("<p>Hi</p>");
    }

    @Test
    void dropsANoteOnAnotherServer() throws Exception {
        var noteId = "https://elsewhere.test/notes/" + UUID.randomUUID();

        handler.handle(updateOf(noteId + "#updates/1", author.uri(), "\"" + noteId + "\""));

        verify(documents, never()).fetch(any());
    }

    @Test
    void fetchesANoteSentAsAnId() throws Exception {
        var noteId = stored();
        when(documents.fetch(eq(URI.create(noteId)))).thenReturn(json.readTree(json.writeValueAsString(
                edit(noteId, "2026-02-01T00:00:00Z", "<p>Fetched</p>"))));

        handler.handle(updateOf(noteId + "#updates/1", author.uri(), "\"" + noteId + "\""));

        assertThat(status(noteId).orElseThrow().get("content")).isEqualTo("<p>Fetched</p>");
    }

    @Test
    void retriesWhenANoteCantBeFetchedYet() throws Exception {
        var noteId = stored();
        when(documents.fetch(eq(URI.create(noteId)))).thenThrow(DocumentFetchException.temporary("down", null));

        assertThatThrownBy(() -> handler.handle(updateOf(noteId + "#updates/1", author.uri(), "\"" + noteId + "\"")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ignoresEditsFromABlockedServer() {
        var noteId = stored();
        blocks.create(host, null);

        handler.handle(update(edit(noteId, "2026-02-01T00:00:00Z", "<p>Blocked</p>")));

        // The block can also have removed the status by now.
        assertThat(status(noteId).map(row -> row.get("content"))).isNotEqualTo(Optional.of("<p>Blocked</p>"));
    }

    @Test
    void anEditOfASuspendedActorsStatusIsntApplied() {
        var noteId = stored();
        jdbc.sql("UPDATE actor SET suspended_at = now() WHERE id = :id").param("id", author.id()).update();

        handler.handle(update(edit(noteId, "2026-02-01T00:00:00Z", "<p>Suspended</p>")));

        assertThat(status(noteId).orElseThrow().get("content")).isEqualTo("<p>Hi</p>");
    }

    @Test
    void ignoresObjectsThatArentNotes() {
        var noteId = stored();
        var question = new HashMap<>(edit(noteId, "2026-02-01T00:00:00Z", "<p>Poll</p>"));
        question.put("type", "Question");

        handler.handle(update(question));

        assertThat(status(noteId).orElseThrow().get("content")).isEqualTo("<p>Hi</p>");
    }

    // Actors

    @Test
    void anActorsUpdateFetchesItsDocumentAgain() throws Exception {
        var embedded = actorDocument(author.uri(), "/embedded-inbox", "embedded-key");
        when(documents.fetch(eq(author.uri())))
                .thenReturn(json.readTree(json.writeValueAsString(actorDocument(author.uri(), "/new-inbox", "new-key"))));

        handler.handle(updateOf(author.uri() + "#updates/1", author.uri(), json.writeValueAsString(embedded)));

        var refreshed = actors.findByUri(author.uri()).orElseThrow();
        assertThat(refreshed.inboxUri()).hasToString(author.uri() + "/new-inbox");
        assertThat(refreshed.publicKeyPem()).isEqualTo("new-key");
    }

    @Test
    void anActorsUpdateSentAsAnIdFetchesItsDocument() throws Exception {
        when(documents.fetch(eq(author.uri())))
                .thenReturn(json.readTree(json.writeValueAsString(actorDocument(author.uri(), "/new-inbox", "new-key"))));

        handler.handle(updateOf(author.uri() + "#updates/1", author.uri(), "\"" + author.uri() + "\""));

        assertThat(actors.findByUri(author.uri()).orElseThrow().inboxUri()).hasToString(author.uri() + "/new-inbox");
    }

    @Test
    void anActorCannotUpdateAnotherActor() throws Exception {
        var other = actorDocument(neighbour.uri(), "/new-inbox", "new-key");

        handler.handle(updateOf(author.uri() + "#updates/1", author.uri(), json.writeValueAsString(other)));

        verify(documents, never()).fetch(any());
        assertThat(actors.findByUri(neighbour.uri()).orElseThrow().inboxUri()).hasToString(neighbour.uri() + "/inbox");
    }

    @Test
    void anActorThatIsntStoredIsLeftAlone() throws Exception {
        var stranger = URI.create("https://" + host + "/users/stranger");

        handler.handle(updateOf(stranger + "#updates/1", stranger,
                json.writeValueAsString(actorDocument(stranger, "/inbox", "key"))));

        verify(documents, never()).fetch(any());
        assertThat(actors.findByUri(stranger)).isEmpty();
    }

    @Test
    void anActorSuspendedWhileItsBeingFetchedIsLeftAlone() throws Exception {
        when(documents.fetch(eq(author.uri()))).thenAnswer(invocation -> {
            jdbc.sql("UPDATE actor SET suspended_at = now() WHERE id = :id").param("id", author.id()).update();
            return json.readTree(json.writeValueAsString(actorDocument(author.uri(), "/new-inbox", "new-key")));
        });

        handler.handle(updateOf(author.uri() + "#updates/1", author.uri(), "\"" + author.uri() + "\""));

        var kept = actors.findByUri(author.uri()).orElseThrow();
        assertThat(kept.inboxUri()).hasToString(author.uri() + "/inbox");
        assertThat(kept.publicKeyPem()).isNull();
    }

    @Test
    void anActorDeletedWhileItsBeingFetchedIsLeftAlone() throws Exception {
        when(documents.fetch(eq(author.uri()))).thenAnswer(invocation -> {
            actors.markRemoteDeleted(author.id());
            return json.readTree(json.writeValueAsString(actorDocument(author.uri(), "/new-inbox", "new-key")));
        });

        handler.handle(updateOf(author.uri() + "#updates/1", author.uri(), "\"" + author.uri() + "\""));

        var kept = actors.findByUri(author.uri()).orElseThrow();
        assertThat(kept.isDeleted()).isTrue();
        assertThat(kept.publicKeyPem()).isNull();
        assertThat(kept.inboxUri()).hasToString(author.uri() + "/inbox");
    }

    @Test
    void anActorWhoseServerRefusesKeepsItsOlderCopy() throws Exception {
        when(documents.fetch(eq(author.uri()))).thenThrow(DocumentFetchException.gone("gone"));

        handler.handle(updateOf(author.uri() + "#updates/1", author.uri(), "\"" + author.uri() + "\""));

        var kept = actors.findByUri(author.uri()).orElseThrow();
        assertThat(kept.isActive()).isTrue();
        assertThat(kept.inboxUri()).hasToString(author.uri() + "/inbox");
    }

    @Test
    void anActorsUpdateIsRetriedWhenItsServerCantBeReached() throws Exception {
        when(documents.fetch(eq(author.uri()))).thenThrow(DocumentFetchException.temporary("down", null));

        assertThatThrownBy(() -> handler.handle(updateOf(author.uri() + "#updates/1", author.uri(), "\"" + author.uri() + "\"")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anActorWhoseNewDocumentCantBeUsedKeepsItsOlderCopy() throws Exception {
        var unusable = new HashMap<>(actorDocument(author.uri(), "/new-inbox", "new-key"));
        unusable.put("inbox", "https://elsewhere.test/inbox");
        when(documents.fetch(eq(author.uri()))).thenReturn(json.readTree(json.writeValueAsString(unusable)));

        assertThatThrownBy(() -> handler.handle(updateOf(author.uri() + "#updates/1", author.uri(), "\"" + author.uri() + "\"")))
                .isInstanceOf(PermanentJobFailure.class);
        assertThat(actors.findByUri(author.uri()).orElseThrow().inboxUri()).hasToString(author.uri() + "/inbox");
    }

    // Helpers

    /**
     * @return the URI of a public status of the author's, published at the start of 2026
     */
    private String stored() {
        var noteId = noteId();
        creates.handle(create(note(noteId, null)));
        assertThat(status(noteId)).isPresent();
        return noteId;
    }

    private String noteId() {
        return "https://" + host + "/notes/" + UUID.randomUUID();
    }

    private Map<String, Object> note(String id, String updated) {
        var note = new HashMap<String, Object>(Map.of(
                "id", id,
                "type", "Note",
                "attributedTo", author.uri().toString(),
                "to", List.of(PUBLIC),
                "cc", List.of(followers),
                "content", "<p>Hi</p>",
                "published", "2026-01-01T00:00:00Z"));
        if (updated != null) {
            note.put("updated", updated);
        }
        return note;
    }

    /**
     * An edited note that was published an hour ago.
     */
    private Map<String, Object> recent(String id) {
        var now = Instant.now();
        var note = edit(id, now.toString(), "<p>Edited</p>");
        note.put("published", now.minus(Duration.ofHours(1)).toString());
        return note;
    }

    private Map<String, Object> edit(String id, String updated, String content) {
        var note = note(id, updated);
        note.put("content", content);
        return note;
    }

    private Map<String, Object> actorDocument(URI uri, String inbox, String key) {
        return Map.of(
                "id", uri.toString(),
                "type", "Person",
                "preferredUsername", uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1),
                "inbox", uri + inbox,
                "followers", uri + "/followers",
                "publicKey", Map.of("id", uri + "#main-key", "owner", uri.toString(), "publicKeyPem", key));
    }

    private InboundActivity create(Map<String, Object> note) {
        var id = note.get("id") + "/activity";
        var body = "{\"id\":\"%s\",\"type\":\"Create\",\"actor\":\"%s\",\"object\":%s}"
                .formatted(id, author.uri(), json.writeValueAsString(note));
        return new InboundActivity(id, author.uri().toString(), "Create", body);
    }

    private InboundActivity update(Map<String, Object> note) {
        return updateOf(note.get("id") + "#updates/" + UUID.randomUUID(), author.uri(), json.writeValueAsString(note));
    }

    private InboundActivity updateOf(String id, URI actor, String object) {
        var body = "{\"id\":\"%s\",\"type\":\"Update\",\"actor\":\"%s\",\"object\":%s}".formatted(id, actor, object);
        return new InboundActivity(id, actor.toString(), "Update", body);
    }

    private Optional<Map<String, Object>> status(String uri) {
        return jdbc.sql("SELECT * FROM status WHERE uri = :uri").param("uri", uri).query().listOfRows().stream().findFirst();
    }

    private Actor remote(String username) {
        var uri = URI.create("https://" + host + "/users/" + username);
        return actors.upsertRemote(new RemoteActorProfile(
                uri, username, host, URI.create(uri + "/inbox"), null, null, null, URI.create(uri + "/followers")));
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
