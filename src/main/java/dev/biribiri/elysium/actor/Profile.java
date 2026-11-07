package dev.biribiri.elysium.actor;

import dev.biribiri.elysium.text.PlainText;

import java.time.Instant;
import java.util.List;

/**
 * What someone shows about themselves. The text is kept as they wrote it, and rendered as HTML when it's read.
 *
 * @param avatar    null if there isn't one
 * @param header    null if there isn't one
 * @param updatedAt null if it's never been changed
 */
public record Profile(
        String displayName,
        String note,
        ProfileImage avatar,
        ProfileImage header,
        List<ProfileField> fields,
        Instant updatedAt) {

    public static final Profile EMPTY = new Profile("", "", null, null, List.of(), null);

    public Profile {
        fields = List.copyOf(fields);
    }

    /**
     * @return an empty string for an empty note, rather than an empty paragraph
     */
    public String noteHtml() {
        return note.isEmpty() ? "" : PlainText.render(note);
    }
}
