package dev.biribiri.elysium.actor;

import dev.biribiri.elysium.text.Hashtags;
import dev.biribiri.elysium.text.PlainText;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.SequencedSet;

/**
 * What someone shows about themselves. A local account's text is kept as they wrote it, and rendered as HTML when it's
 * read. An account on another server's comes from its actor document, which already has HTML.
 *
 * @param note      the bio as text. For an account on another server, it's what its HTML says, for finding hashtags and
 *                  emoji in.
 * @param avatar    null if there isn't one
 * @param header    null if there isn't one
 * @param manuallyApprovesFollowers whether requests to follow the account wait for its approval
 * @param updatedAt null if it's never been changed
 * @param remote    what only an account on another server has, or null for a local one
 */
public record Profile(
        String displayName,
        String note,
        ProfileImage avatar,
        ProfileImage header,
        List<ProfileField> fields,
        boolean manuallyApprovesFollowers,
        Instant updatedAt,
        Remote remote) {

    public static final Profile EMPTY = new Profile("", "", null, null, List.of(), false, null);

    /**
     * What an account on another server's actor document says that a local profile doesn't need.
     *
     * @param noteHtml  the bio, sanitized
     * @param url       its profile page, or null if the document doesn't give one
     * @param bot       whether it's an automated account, as a {@code Service} or {@code Application} is
     * @param group     whether it's a group, as a {@code Group} is
     * @param published when its server says it was created, or null if it doesn't
     */
    public record Remote(String noteHtml, URI url, boolean bot, boolean group, Instant published) {
    }

    public Profile {
        fields = List.copyOf(fields);
    }

    /**
     * A local account's.
     */
    public Profile(
            String displayName,
            String note,
            ProfileImage avatar,
            ProfileImage header,
            List<ProfileField> fields,
            boolean manuallyApprovesFollowers,
            Instant updatedAt) {
        this(displayName, note, avatar, header, fields, manuallyApprovesFollowers, updatedAt, null);
    }

    /**
     * Mentions in a local note aren't linked, as they would have to be looked up each time it's read.
     *
     * @param tagBase the base URL that hashtags link under
     * @return an empty string for an empty note, rather than an empty paragraph
     */
    public String noteHtml(URI tagBase) {
        if (remote != null) {
            return remote.noteHtml();
        }
        return note.isEmpty() ? "" : PlainText.render(note, mention -> null, tagBase);
    }

    /**
     * What an actor document lists in its {@code tag}, as Mastodon's do.
     */
    public SequencedSet<String> hashtags() {
        return Hashtags.names(note);
    }

    /**
     * Everything in the profile that can name a custom emoji, as Mastodon finds them.
     */
    public List<String> texts() {
        var texts = new ArrayList<String>();
        texts.add(displayName);
        texts.add(note);
        fields.forEach(field -> {
            texts.add(field.name());
            texts.add(field.value());
        });
        return texts;
    }
}
