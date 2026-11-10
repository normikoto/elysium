package dev.biribiri.elysium.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.content.Status;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * A status with what the templates need to show it.
 *
 * @param authorName  the author's address, which leaves out the domain for a local account
 * @param authorUrl   the author's profile
 * @param url         the status's page, which for a status from another server is on that server
 * @param publishedAt when the status was published, for people to read
 * @param boostedBy   the username of the local account whose boost this is shown for, or null
 * @param attachments the status's attachments, in order
 */
record StatusView(Status status, String authorName, String authorUrl, String url, String publishedAt, String boostedBy,
                  List<AttachmentView> attachments) {

    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    static StatusView from(Status status, Actor author) {
        return new StatusView(
                status,
                "@" + author.username(),
                "/users/" + author.username(),
                status.uri().getRawPath(),
                DISPLAY.format(status.publishedAt()),
                null,
                List.of());
    }

    /**
     * @param local whether the author is a local account
     */
    static StatusView of(Status status, Actor author, boolean local) {
        if (local) {
            return from(status, author);
        }
        return new StatusView(
                status,
                "@" + author.username() + "@" + author.domain(),
                author.uri().toString(),
                (status.url() == null ? status.uri() : status.url()).toString(),
                DISPLAY.format(status.publishedAt()),
                null,
                List.of());
    }

    /**
     * @param local whether the boosted status's author is a local account
     */
    static StatusView boosted(Status status, Actor author, boolean local, Actor booster) {
        var view = of(status, author, local);
        return new StatusView(status, view.authorName, view.authorUrl, view.url, view.publishedAt, booster.username(),
                List.of());
    }

    StatusView withAttachments(List<AttachmentView> attachments) {
        return new StatusView(status, authorName, authorUrl, url, publishedAt, boostedBy, List.copyOf(attachments));
    }
}
