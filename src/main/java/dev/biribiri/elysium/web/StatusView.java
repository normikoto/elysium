package dev.biribiri.elysium.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.content.Status;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * A status with what the templates need to show it.
 *
 * @param username    the author's username
 * @param path        the path of the status's page
 * @param publishedAt when the status was published, for people to read
 */
record StatusView(Status status, String username, String path, String publishedAt) {

    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    static StatusView from(Status status, Actor author) {
        return new StatusView(
                status,
                author.username(),
                status.uri().getRawPath(),
                DISPLAY.format(status.publishedAt()));
    }
}
