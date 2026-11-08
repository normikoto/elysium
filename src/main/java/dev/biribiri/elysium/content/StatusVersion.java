package dev.biribiri.elysium.content;

import java.time.Instant;

/**
 * One version of a status, as it was before an edit, or as it is now.
 *
 * @param text     the source text, as {@link Status#text} has it
 * @param language the language code, or null if not set
 * @param since    when this version became current: when the status was published, or edited
 */
public record StatusVersion(
        String text, String content, String spoilerText, String language, boolean sensitive, Instant since) {

    static StatusVersion current(Status status) {
        return new StatusVersion(
                status.text(),
                status.content(),
                status.spoilerText(),
                status.language(),
                status.sensitive(),
                status.editedAt() == null ? status.publishedAt() : status.editedAt());
    }
}
