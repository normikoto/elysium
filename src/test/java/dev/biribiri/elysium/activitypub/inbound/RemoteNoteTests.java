package dev.biribiri.elysium.activitypub.inbound;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteNoteTests {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void convertsAMobilizonEventToItsTitleAndLink() {
        var note = read("""
                {"id": "https://m.test/events/1", "type": "Event", "attributedTo": "https://m.test/@a",
                 "name": "Meetup", "content": "<p>Details</p>", "startTime": "2026-12-01T18:00:00Z",
                 "url": "https://m.test/events/1"}
                """);

        assertThat(note.content()).isEqualTo("<p>Meetup</p><p><a href=\"https://m.test/events/1\" "
                + "rel=\"nofollow noopener noreferrer\" target=\"_blank\">https://m.test/events/1</a></p>");
    }

    @Test
    void linksToTheIdWhenThereIsNoUrl() {
        var note = read("""
                {"id": "https://w.test/a/1", "type": "Article", "attributedTo": "https://w.test/@a"}
                """);

        assertThat(note.content()).contains("href=\"https://w.test/a/1\"");
        assertThat(note.url()).isNull();
    }

    @Test
    void readsAConvertedObjectsSummaryAsTextNotAContentWarning() {
        var note = read("""
                {"id": "https://w.test/a/1", "type": "Article", "attributedTo": "https://w.test/@a",
                 "summary": "<p>First</p><p>Second <script>x()</script></p>"}
                """);

        assertThat(note.summary()).isNull();
        assertThat(note.content()).startsWith("<p>First</p><p>Second</p><p><a ");
    }

    @Test
    void keepsAConvertedObjectsTitleFromLookingLikeAVote() {
        var note = read("""
                {"id": "https://w.test/a/1", "type": "Page", "attributedTo": "https://w.test/@a",
                 "name": "Option A", "inReplyTo": "https://elysium.example/users/b/statuses/1"}
                """);

        assertThat(note.name()).isNull();
    }

    @Test
    void readsANotesContentAndNameAsTheyAre() {
        var note = read("""
                {"id": "https://n.test/1", "type": "Note", "attributedTo": "https://n.test/@a",
                 "name": "Option A", "summary": "CW", "content": "<p>Hi</p>"}
                """);

        assertThat(note.content()).isEqualTo("<p>Hi</p>");
        assertThat(note.summary()).isEqualTo("CW");
        assertThat(note.name()).isEqualTo("Option A");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Place", "Tombstone", "Collection", "Person"})
    void ignoresOtherTypes(String type) {
        assertThat(RemoteNote.read(json.readTree("""
                {"id": "https://n.test/1", "type": "%s", "attributedTo": "https://n.test/@a"}
                """.formatted(type)))).isEmpty();
    }

    @Test
    void leavesOutAGroupAttributedAlongsideAnAuthor() {
        var note = read("""
                {"id": "https://p.test/videos/1", "type": "Video",
                 "attributedTo": [{"type": "Group", "id": "https://p.test/video-channels/c"},
                                  {"type": "Person", "id": "https://p.test/accounts/a"}]}
                """);

        assertThat(note.attributedTo()).containsExactly("https://p.test/accounts/a");
    }

    @Test
    void keepsAGroupThatIsTheOnlyAuthorAndOneSentAsAnId() {
        var alone = read("""
                {"id": "https://l.test/post/1", "type": "Page",
                 "attributedTo": [{"type": "Group", "id": "https://l.test/c/a"}]}
                """);
        var byId = read("""
                {"id": "https://l.test/post/2", "type": "Page",
                 "attributedTo": ["https://l.test/c/a", {"type": "Person", "id": "https://l.test/u/a"}]}
                """);

        assertThat(alone.attributedTo()).containsExactly("https://l.test/c/a");
        assertThat(byId.attributedTo()).containsExactly("https://l.test/c/a", "https://l.test/u/a");
    }

    @Test
    void doesNotReadALinkAttachmentAsMedia() {
        var note = read("""
                {"id": "https://l.test/post/1", "type": "Page", "attributedTo": "https://l.test/u/a",
                 "attachment": [{"type": "Link", "href": "https://elsewhere.test/story"}]}
                """);

        assertThat(note.attachments()).isEmpty();
    }

    @Test
    void treatsAStatusTypeAlongsideAConvertedOneAsTheStatusType() {
        var note = read("""
                {"id": "https://n.test/1", "type": ["Article", "Note"], "attributedTo": "https://n.test/@a",
                 "content": "<p>Hi</p>"}
                """);

        assertThat(note.content()).isEqualTo("<p>Hi</p>");
    }

    private RemoteNote read(String document) {
        return RemoteNote.read(json.readTree(document)).orElseThrow();
    }
}
