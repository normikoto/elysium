package dev.biribiri.elysium.actor;

import dev.biribiri.elysium.text.Hashtags;
import dev.biribiri.elysium.text.PlainText;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.SequencedSet;

/**
 * What someone shows about themselves. The text is kept as they wrote it, and rendered as HTML when it's read.
 *
 * @param avatar    null if there isn't one
 * @param header    null if there isn't one
 * @param manuallyApprovesFollowers whether requests to follow the account wait for its approval
 * @param updatedAt null if it's never been changed
 */
public record Profile(
        String displayName,
        String note,
        ProfileImage avatar,
        ProfileImage header,
        List<ProfileField> fields,
        boolean manuallyApprovesFollowers,
        Instant updatedAt) {

    public static final Profile EMPTY = new Profile("", "", null, null, List.of(), false, null);

    public Profile {
        fields = List.copyOf(fields);
    }

    /**
     * Mentions in a note aren't linked, as they would have to be looked up each time it's read.
     *
     * @param tagBase the base URL that hashtags link under
     * @return an empty string for an empty note, rather than an empty paragraph
     */
    public String noteHtml(URI tagBase) {
        return note.isEmpty() ? "" : PlainText.render(note, mention -> null, tagBase);
    }

    /**
     * What an actor document lists in its {@code tag}, as Mastodon's do.
     */
    public SequencedSet<String> hashtags() {
        return Hashtags.names(note);
    }
}
