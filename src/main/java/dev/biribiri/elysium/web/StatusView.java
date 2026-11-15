package dev.biribiri.elysium.web;

import dev.biribiri.elysium.text.EmojiHtml;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.content.Status;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * A status with what the templates need to show it.
 *
 * @param authorName  the author's address, which leaves out the domain for a local account
 * @param authorUrl   the author's profile
 * @param url         the status's page, which for a status from another server is on that server
 * @param publishedAt when the status was published, for people to read
 * @param boostedBy   the username of the local account whose boost this is shown for, or null
 * @param attachments the status's attachments, in order
 * @param poll        the status's poll, or null if it has none
 * @param emojis      the image of each custom emoji the status has, by shortcode
 */
record StatusView(Status status, String authorName, String authorUrl, String url, String publishedAt, String boostedBy,
                  List<AttachmentView> attachments, PollView poll, Map<String, String> emojis) {

    static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    static StatusView from(Status status, Actor author) {
        return new StatusView(
                status,
                "@" + author.username(),
                "/users/" + author.username(),
                status.uri().getRawPath(),
                DISPLAY.format(status.publishedAt()),
                null,
                List.of(),
                null,
                Map.of());
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
                List.of(),
                null,
                Map.of());
    }

    /**
     * @param local whether the boosted status's author is a local account
     */
    static StatusView boosted(Status status, Actor author, boolean local, Actor booster) {
        var view = of(status, author, local);
        return new StatusView(status, view.authorName, view.authorUrl, view.url, view.publishedAt, booster.username(),
                List.of(), null, Map.of());
    }

    StatusView withAttachments(List<AttachmentView> attachments) {
        return new StatusView(status, authorName, authorUrl, url, publishedAt, boostedBy, List.copyOf(attachments), poll,
                emojis);
    }

    StatusView withPoll(PollView poll) {
        return new StatusView(status, authorName, authorUrl, url, publishedAt, boostedBy, attachments, poll, emojis);
    }

    StatusView withEmojis(Map<String, String> emojis) {
        return new StatusView(status, authorName, authorUrl, url, publishedAt, boostedBy, attachments, poll,
                Map.copyOf(emojis));
    }

    /**
     * The content, which is stored safe to show, with its emoji as images.
     */
    public String contentHtml() {
        return EmojiHtml.render(status.content(), emojis);
    }

    /**
     * The content warning, which is plain text, escaped, with its emoji as images.
     */
    public String spoilerHtml() {
        return EmojiHtml.renderText(status.spoilerText(), emojis);
    }
}
