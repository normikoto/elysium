package dev.biribiri.elysium.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.content.Status;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * A status with what the templates need to show it.
 *
 * @param authorName  the author's address, which leaves out the domain for a local account
 * @param authorUrl   the author's profile
 * @param url         the status's page, which for a status from another server is on that server
 * @param publishedAt when the status was published, for people to read
 * @param boostedBy   the username of the local account whose boost this is shown for, or null
 */
record StatusView(Status status, String authorName, String authorUrl, String url, String publishedAt, String boostedBy) {

    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    static StatusView from(Status status, Actor author) {
        return new StatusView(
                status,
                "@" + author.username(),
                "/users/" + author.username(),
                status.uri().getRawPath(),
                DISPLAY.format(status.publishedAt()),
                null);
    }

    /**
     * @param local whether the boosted status's author is a local account
     */
    static StatusView boosted(Status status, Actor author, boolean local, Actor booster) {
        if (local) {
            var view = from(status, author);
            return new StatusView(status, view.authorName, view.authorUrl, view.url, view.publishedAt, booster.username());
        }
        return new StatusView(
                status,
                "@" + author.username() + "@" + author.domain(),
                author.uri().toString(),
                (status.url() == null ? status.uri() : status.url()).toString(),
                DISPLAY.format(status.publishedAt()),
                booster.username());
    }
}
