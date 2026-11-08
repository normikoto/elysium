package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Calls the handler directly with no worker running, as {@link FollowIntegrationTests} does. Remote actors are stored
 * beforehand, so they're fresh and aren't fetched.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://create.example", "elysium.jobs.worker-enabled=false"})
class CreateIntegrationTests {

    private static final String PUBLIC = "https://www.w3.org/ns/activitystreams#Public";

    @Autowired
    private CreateHandler handler;
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

    private Actor follower;
    private Actor bystander;
    private Actor author;
    private String host;
    private String followers;

    @BeforeEach
    void setUp() {
        follower = local();
        bystander = local();
        host = "h" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/s");
        followers = uri + "/followers";
        author = actors.upsertRemote(new RemoteActorProfile(
                uri, "s", host, URI.create(uri + "/inbox"), null, null, null, URI.create(followers)));
        follow(follower, author);
    }

    @Test
    void storesAPublicNoteWithItsDetails() {
        var noteId = noteId();
        var note = Map.<String, Object>of(
                "id", noteId,
                "type", "Note",
                "attributedTo", author.uri().toString(),
                "to", List.of(PUBLIC),
                "cc", List.of(followers),
                "content", "<p>Hello <script>alert(1)</script><b>there</b></p>",
                "contentMap", Map.of("de", "<p>Hallo</p>"),
                "summary", "<p>Careful</p>",
                "url", "https://" + host + "/@s/1",
                "published", "2026-01-02T03:04:05Z");

        handler.handle(create(note));

        var row = status(noteId).orElseThrow();
        assertThat(row.get("actor_id")).isEqualTo(author.id());
        assertThat(row.get("visibility")).isEqualTo("public");
        assertThat(row.get("content")).isEqualTo("<p>Hello <b>there</b></p>");
        assertThat(row.get("text")).isEqualTo("Hello there");
        assertThat(row.get("spoiler_text")).isEqualTo("Careful");
        assertThat(row.get("sensitive")).isEqualTo(true);
        assertThat(row.get("language")).isEqualTo("de");
        assertThat(row.get("url")).isEqualTo("https://" + host + "/@s/1");
        assertThat(((Timestamp) row.get("published_at")).toInstant()).hasToString("2026-01-02T03:04:05Z");
        assertThat(row.get("reply")).isEqualTo(false);
    }

    @Test
    void readsVisibilityFromTheNotesAddressing() {
        var unlisted = noteId();
        var followersOnly = noteId();
        var direct = noteId();

        handler.handle(create(note(unlisted, List.of(followers), List.of(PUBLIC))));
        handler.handle(create(note(followersOnly, List.of(followers), List.of())));
        handler.handle(create(note(direct, List.of(follower.uri().toString()), List.of())));

        assertThat(status(unlisted).orElseThrow().get("visibility")).isEqualTo("unlisted");
        assertThat(status(followersOnly).orElseThrow().get("visibility")).isEqualTo("private");
        assertThat(status(direct).orElseThrow().get("visibility")).isEqualTo("direct");
    }

    @Test
    void showsADirectNoteOnlyToTheAccountsItsAddressedTo() {
        var noteId = noteId();

        handler.handle(create(tagged(note(noteId, List.of(follower.uri().toString()), List.of()), follower.uri())));

        var id = statusId(noteId);
        assertThat(mentions(id)).containsExactlyEntriesOf(Map.of(follower.id(), false));
        assertThat(statuses.findVisible(id, Optional.of(follower.id()))).isPresent();
        assertThat(statuses.findVisible(id, Optional.of(bystander.id()))).isEmpty();
        assertThat(statuses.findVisible(id, Optional.empty())).isEmpty();
    }

    @Test
    void mentionsAnAccountSilentlyWhenItsAddressedWithoutATag() {
        var noteId = noteId();

        handler.handle(create(note(noteId, List.of(follower.uri().toString()), List.of())));

        var id = statusId(noteId);
        assertThat(mentions(id)).containsExactlyEntriesOf(Map.of(follower.id(), true));
        assertThat(statuses.findMentions(List.of(id))).isEmpty();
        assertThat(statuses.findVisible(id, Optional.of(follower.id()))).isPresent();
    }

    @Test
    void aTagAloneDoesNotShowAFollowersOnlyOrDirectNoteToAnAccount() {
        for (var to : List.of(List.of(followers), List.of(follower.uri().toString()))) {
            var noteId = noteId();

            handler.handle(create(tagged(note(noteId, to, List.of()), bystander.uri())));

            var id = statusId(noteId);
            assertThat(mentions(id)).doesNotContainKey(bystander.id());
            assertThat(statuses.findVisible(id, Optional.of(bystander.id()))).isEmpty();
        }
    }

    @Test
    void mentionsATaggedAccountInAPublicNoteWithoutItBeingAddressed() {
        var noteId = noteId();

        handler.handle(create(tagged(note(noteId, List.of(PUBLIC), List.of(followers)), bystander.uri())));

        assertThat(mentions(statusId(noteId))).containsExactlyEntriesOf(Map.of(bystander.id(), false));
    }

    @Test
    void onlyMentionsStoredAccountsOnOtherServersWithoutFetchingAny() {
        var known = stranger();
        var unknown = URI.create("https://h" + UUID.randomUUID() + ".test/users/x");
        var noteId = noteId();

        handler.handle(create(tagged(note(noteId, List.of(PUBLIC), List.of(followers)), known, unknown)));

        var knownId = actors.findByUri(known).orElseThrow().id();
        assertThat(mentions(statusId(noteId))).containsExactlyEntriesOf(Map.of(knownId, false));
        verifyNoInteractions(documents);
    }

    @Test
    void storesANoteThatAddressesALocalAccountWithoutAFollow() {
        jdbc.sql("DELETE FROM follow WHERE followed_id = :id").param("id", author.id()).update();
        var noteId = noteId();

        handler.handle(create(note(noteId, List.of(PUBLIC), List.of(bystander.uri().toString()))));

        assertThat(status(noteId)).isPresent();
    }

    @Test
    void ignoresANoteThatIsntForAnyLocalAccount() {
        jdbc.sql("DELETE FROM follow WHERE followed_id = :id").param("id", author.id()).update();
        var noteId = noteId();

        handler.handle(create(note(noteId, List.of(PUBLIC), List.of(followers))));

        assertThat(status(noteId)).isEmpty();
    }

    @Test
    void dropsANoteAttributedToSomeoneElse() {
        var noteId = noteId();
        var note = new HashMap<>(note(noteId, List.of(PUBLIC), List.of(followers)));
        note.put("attributedTo", "https://" + host + "/users/other");

        handler.handle(create(note));

        assertThat(status(noteId)).isEmpty();
    }

    @Test
    void dropsANoteOnAnotherServer() {
        var noteId = "https://elsewhere.test/notes/" + UUID.randomUUID();

        handler.handle(create(note(noteId, List.of(PUBLIC), List.of(followers))));

        assertThat(status(noteId)).isEmpty();
    }

    @Test
    void ignoresObjectsThatArentNotes() {
        var noteId = noteId();
        var article = new HashMap<>(note(noteId, List.of(PUBLIC), List.of(followers)));
        article.put("type", "Article");

        handler.handle(create(article));

        assertThat(status(noteId)).isEmpty();
    }

    @Test
    void dropsNotesFromSuspendedOrBlockedActors() {
        var suspended = noteId();
        jdbc.sql("UPDATE actor SET suspended_at = now() WHERE id = :id").param("id", author.id()).update();
        assertThatThrownBy(() -> handler.handle(create(note(suspended, List.of(PUBLIC), List.of(followers)))))
                .isInstanceOf(PermanentJobFailure.class);
        jdbc.sql("UPDATE actor SET suspended_at = NULL WHERE id = :id").param("id", author.id()).update();

        var blocked = noteId();
        blocks.create(host, null);
        handler.handle(create(note(blocked, List.of(PUBLIC), List.of(followers))));

        assertThat(status(suspended)).isEmpty();
        assertThat(status(blocked)).isEmpty();
    }

    @Test
    void storesARepeatOnce() {
        var noteId = noteId();
        var activity = create(note(noteId, List.of(PUBLIC), List.of(followers)));

        handler.handle(activity);
        handler.handle(activity);

        assertThat(jdbc.sql("SELECT count(*) FROM status WHERE uri = :uri").param("uri", noteId)
                .query(Long.class).single()).isOne();
    }

    @Test
    void fetchesANoteSentAsAnId() throws Exception {
        var noteId = noteId();
        when(documents.fetch(eq(URI.create(noteId))))
                .thenReturn(json.readTree(json.writeValueAsString(note(noteId, List.of(PUBLIC), List.of(followers)))));

        handler.handle(createOf(noteId, "\"" + noteId + "\""));

        assertThat(status(noteId)).isPresent();
    }

    @Test
    void retriesWhenANoteCantBeFetchedYet() throws Exception {
        var noteId = noteId();
        when(documents.fetch(eq(URI.create(noteId)))).thenThrow(DocumentFetchException.temporary("down", null));

        assertThatThrownBy(() -> handler.handle(createOf(noteId, "\"" + noteId + "\"")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void doesNotFetchANoteOnAnotherServer() throws Exception {
        var noteId = "https://elsewhere.test/notes/" + UUID.randomUUID();

        handler.handle(createOf(noteId, "\"" + noteId + "\""));

        verify(documents, never()).fetch(any());
    }

    @Test
    void dropsAFetchedNoteWithAnotherId() throws Exception {
        var noteId = noteId();
        when(documents.fetch(eq(URI.create(noteId))))
                .thenReturn(json.readTree(json.writeValueAsString(note(noteId(), List.of(PUBLIC), List.of(followers)))));

        handler.handle(createOf(noteId, "\"" + noteId + "\""));

        assertThat(status(noteId)).isEmpty();
    }

    @Test
    void linksARepliesParentWhenItsStored() {
        var parent = noteId();
        handler.handle(create(note(parent, List.of(PUBLIC), List.of(followers))));
        var reply = noteId();
        var replyNote = new HashMap<>(note(reply, List.of(PUBLIC), List.of(followers)));
        replyNote.put("inReplyTo", parent);
        var orphan = noteId();
        var orphanNote = new HashMap<>(note(orphan, List.of(PUBLIC), List.of(followers)));
        orphanNote.put("inReplyTo", "https://elsewhere.test/notes/1");

        handler.handle(create(replyNote));
        handler.handle(create(orphanNote));

        var linked = status(reply).orElseThrow();
        assertThat(linked.get("reply")).isEqualTo(true);
        assertThat(linked.get("in_reply_to_id")).isEqualTo(status(parent).orElseThrow().get("id"));
        assertThat(linked.get("in_reply_to_account_id")).isEqualTo(author.id());
        var unlinked = status(orphan).orElseThrow();
        assertThat(unlinked.get("reply")).isEqualTo(true);
        assertThat(unlinked.get("in_reply_to_id")).isNull();
        assertThat(unlinked.get("in_reply_to_uri")).isEqualTo("https://elsewhere.test/notes/1");
    }

    @Test
    void linksARepliesParentWhenTheParentArrivesLater() {
        var parent = noteId();
        var reply = noteId();
        var replyNote = new HashMap<>(note(reply, List.of(PUBLIC), List.of(followers)));
        replyNote.put("inReplyTo", parent);

        handler.handle(create(replyNote));
        handler.handle(create(note(parent, List.of(PUBLIC), List.of(followers))));

        var linked = status(reply).orElseThrow();
        assertThat(linked.get("in_reply_to_id")).isEqualTo(status(parent).orElseThrow().get("id"));
        assertThat(linked.get("in_reply_to_account_id")).isEqualTo(author.id());
    }

    @Test
    void storesAPublicReplyToAFollowedAccountFromSomeoneNobodyFollows() {
        var parent = noteId();
        handler.handle(create(note(parent, List.of(PUBLIC), List.of(followers))));
        var replier = stranger();
        var unlisted = replierNoteId(replier);
        var publicReply = replierNoteId(replier);

        handler.handle(reply(replier, publicReply, parent, List.of(PUBLIC), List.of(replier + "/followers")));
        handler.handle(reply(replier, unlisted, parent, List.of(replier + "/followers"), List.of(PUBLIC)));

        assertThat(status(publicReply).orElseThrow().get("in_reply_to_id")).isEqualTo(status(parent).orElseThrow().get("id"));
        assertThat(status(unlisted).orElseThrow().get("visibility")).isEqualTo("unlisted");
    }

    @Test
    void storesAPublicReplyToALocalAccountsStatusWithoutAddressingThem() {
        var parent = statuses.create(bystander.id(), new NewStatus("hello", null, null, Visibility.PUBLIC, false, null));
        var replier = stranger();
        var reply = replierNoteId(replier);

        handler.handle(reply(replier, reply, parent.uri().toString(), List.of(PUBLIC), List.of()));

        assertThat(status(reply).orElseThrow().get("in_reply_to_id")).isEqualTo(parent.id());
    }

    @Test
    void ignoresRepliesThatNobodyHereFollowsTheParentOf() {
        var parent = noteId();
        handler.handle(create(note(parent, List.of(PUBLIC), List.of(followers))));
        var replier = stranger();
        var followersOnly = replierNoteId(replier);
        var toUnstored = replierNoteId(replier);
        var afterUnfollow = replierNoteId(replier);

        handler.handle(reply(replier, followersOnly, parent, List.of(replier + "/followers"), List.of(author.uri().toString())));
        handler.handle(reply(replier, toUnstored, "https://" + host + "/notes/missing", List.of(PUBLIC), List.of()));
        jdbc.sql("DELETE FROM follow WHERE followed_id = :id").param("id", author.id()).update();
        handler.handle(reply(replier, afterUnfollow, parent, List.of(PUBLIC), List.of()));

        assertThat(status(followersOnly)).isEmpty();
        assertThat(status(toUnstored)).isEmpty();
        assertThat(status(afterUnfollow)).isEmpty();
    }

    @Test
    void linksAReplyToASuspendedAuthorsStatus() {
        var other = actors.upsertRemote(new RemoteActorProfile(
                URI.create("https://" + host + "/users/other"), "other", host,
                URI.create("https://" + host + "/users/other/inbox"), null, null, null, null));
        var parent = "https://" + host + "/notes/" + UUID.randomUUID();
        var parentNote = new HashMap<>(note(parent, List.of(PUBLIC), List.of(follower.uri().toString())));
        parentNote.put("attributedTo", other.uri().toString());
        handler.handle(createOf(parent + "/activity", other.uri(), json.writeValueAsString(parentNote)));
        jdbc.sql("UPDATE actor SET suspended_at = now() WHERE id = :id").param("id", other.id()).update();
        var reply = noteId();
        var replyNote = new HashMap<>(note(reply, List.of(PUBLIC), List.of(followers)));
        replyNote.put("inReplyTo", parent);

        handler.handle(create(replyNote));

        assertThat(status(reply).orElseThrow().get("in_reply_to_account_id")).isEqualTo(other.id());
    }

    @Test
    void takesTheFirstValidLanguage() {
        var noteId = noteId();
        var note = new HashMap<>(note(noteId, List.of(PUBLIC), List.of(followers)));
        var contentMap = new LinkedHashMap<String, String>();
        contentMap.put("not a language", "<p>Hi</p>");
        contentMap.put("pt-BR", "<p>Oi</p>");
        note.put("contentMap", contentMap);

        handler.handle(create(note));

        assertThat(status(noteId).orElseThrow().get("language")).isEqualTo("pt-BR");
    }

    // Helpers

    private String noteId() {
        return "https://" + host + "/notes/" + UUID.randomUUID();
    }

    private Map<String, Object> note(String id, List<String> to, List<String> cc) {
        return Map.of(
                "id", id,
                "type", "Note",
                "attributedTo", author.uri().toString(),
                "to", to,
                "cc", cc,
                "content", "<p>Hi</p>");
    }

    private static Map<String, Object> tagged(Map<String, Object> note, URI... mentioned) {
        var tagged = new HashMap<>(note);
        tagged.put("tag", Stream.of(mentioned)
                .map(uri -> Map.of("type", "Mention", "href", uri.toString(), "name", "@x@" + uri.getHost()))
                .toList());
        return tagged;
    }

    private UUID statusId(String uri) {
        return (UUID) status(uri).orElseThrow().get("id");
    }

    private Map<UUID, Boolean> mentions(UUID statusId) {
        var mentions = new HashMap<UUID, Boolean>();
        jdbc.sql("SELECT actor_id, silent FROM status_mention WHERE status_id = :id")
                .param("id", statusId)
                .query(rs -> {
                    mentions.put(rs.getObject("actor_id", UUID.class), rs.getBoolean("silent"));
                });
        return mentions;
    }

    private InboundActivity create(Map<String, Object> note) {
        return createOf(note.get("id") + "/activity", json.writeValueAsString(note));
    }

    private InboundActivity createOf(String id, String object) {
        return createOf(id, author.uri(), object);
    }

    private InboundActivity createOf(String id, URI actor, String object) {
        var body = "{\"id\":\"%s\",\"type\":\"Create\",\"actor\":\"%s\",\"object\":%s}".formatted(id, actor, object);
        return new InboundActivity(id, actor.toString(), "Create", body);
    }

    /**
     * A remote actor on another server, whom nobody here follows.
     */
    private URI stranger() {
        var strangerHost = "h" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + strangerHost + "/users/c");
        actors.upsertRemote(new RemoteActorProfile(
                uri, "c", strangerHost, URI.create(uri + "/inbox"), null, null, null, URI.create(uri + "/followers")));
        return uri;
    }

    private static String replierNoteId(URI replier) {
        return "https://" + replier.getHost() + "/notes/" + UUID.randomUUID();
    }

    private InboundActivity reply(URI replier, String id, String parent, List<String> to, List<String> cc) {
        var note = new HashMap<>(note(id, to, cc));
        note.put("attributedTo", replier.toString());
        note.put("inReplyTo", parent);
        return createOf(id + "/activity", replier, json.writeValueAsString(note));
    }

    private Optional<Map<String, Object>> status(String uri) {
        return jdbc.sql("SELECT * FROM status WHERE uri = :uri").param("uri", uri).query().listOfRows().stream().findFirst();
    }

    private Actor local() {
        var name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return actors.createLocalIfAbsent(name, actor -> {
        }).orElseThrow();
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
