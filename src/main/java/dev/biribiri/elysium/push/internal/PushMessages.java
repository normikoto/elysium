package dev.biribiri.elysium.push.internal;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.content.ReactionService;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.notification.Notification;
import dev.biribiri.elysium.text.RemoteHtml;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What a push message says, in the form Mastodon sends it. Clients show the title and body as they are, and use the
 * token and ID to fetch the notification itself, so the text only has to be short and in English.
 */
@Component
public class PushMessages {

    static final int MAX_BODY = 140;
    private static final String PLACEHOLDER_AVATAR = "/images/missing.png";

    private final ActorService actors;
    private final ProfileService profiles;
    private final StatusService statuses;
    private final ReactionService reactions;
    private final InstanceProperties instance;
    private final JsonMapper json;

    PushMessages(ActorService actors, ProfileService profiles, StatusService statuses, ReactionService reactions,
                 InstanceProperties instance, JsonMapper json) {
        this.actors = actors;
        this.profiles = profiles;
        this.statuses = statuses;
        this.reactions = reactions;
        this.instance = instance;
        this.json = json;
    }

    /**
     * @return the message as UTF-8 JSON, or empty if its sender is gone
     */
    public Optional<byte[]> render(Notification notification, UUID recipientId, String accessToken) {
        var sender = actors.findById(notification.accountId()).filter(Actor::isActive);
        if (sender.isEmpty()) {
            return Optional.empty();
        }
        var profile = profiles.find(sender.get().id());
        var name = profile.displayName().isBlank() ? sender.get().username() : profile.displayName();
        var status = notification.statusId() == null
                ? Optional.<Status>empty()
                : statuses.findVisible(notification.statusId(), Optional.of(recipientId));

        var message = new LinkedHashMap<String, Object>();
        message.put("access_token", accessToken);
        message.put("preferred_locale", "en");
        message.put("notification_id", notification.id().toString());
        message.put("notification_type", notification.type());
        message.put("icon", profile.avatar() == null
                ? instance.resolve(PLACEHOLDER_AVATAR).toString()
                : profile.avatar().url(instance, true).toString());
        message.put("title", title(notification, name, status));
        message.put("body", status.map(PushMessages::body).orElseGet(() -> "@" + acct(sender.get())));
        var rendered = json.writeValueAsBytes(message);
        if (rendered.length > WebPushEncryption.MAX_PLAINTEXT) {
            // Only a very long name or token could do this, and a message without its body is still useful.
            message.put("body", "");
            rendered = json.writeValueAsBytes(message);
        }
        return rendered.length > WebPushEncryption.MAX_PLAINTEXT ? Optional.empty() : Optional.of(rendered);
    }

    private String title(Notification notification, String name, Optional<Status> status) {
        return switch (notification.type()) {
            case "mention" -> status.filter(s -> s.visibility() == Visibility.DIRECT).isPresent()
                    ? "New private mention from " + name
                    : "You were mentioned by " + name;
            case "status" -> name + " just posted";
            case "reblog" -> name + " boosted your post";
            case "follow" -> name + " is now following you";
            case "follow_request" -> name + " has requested to follow you";
            case "favourite" -> name + " favorited your post";
            case "poll" -> "A poll by " + name + " has ended";
            case "update" -> name + " edited a post";
            case "quote" -> name + " quoted your post";
            case "quoted_update" -> name + " edited a post you have quoted";
            case "admin.sign_up" -> name + " signed up";
            case "admin.report" -> name + " filed a report";
            case Notification.REACTION -> name + " reacted with " + reaction(notification.reactionId()) + " to your post";
            default -> "New notification from " + name;
        };
    }

    private String reaction(UUID reactionId) {
        if (reactionId == null) {
            return "an emoji";
        }
        var reaction = reactions.findAll(List.of(reactionId)).get(reactionId);
        if (reaction == null) {
            return "an emoji";
        }
        return reaction.emojiId() == null ? reaction.name() : ":" + reaction.name() + ":";
    }

    /**
     * A content warning is shown instead of what it hides, as clients would show it.
     */
    static String body(Status status) {
        var text = status.spoilerText() != null && !status.spoilerText().isBlank()
                ? status.spoilerText()
                : RemoteHtml.plainText(status.content() == null ? "" : status.content());
        return truncate(text.replaceAll("\\s+", " ").strip());
    }

    static String truncate(String text) {
        if (text.codePointCount(0, text.length()) <= MAX_BODY) {
            return text;
        }
        var end = text.offsetByCodePoints(0, MAX_BODY - 1);
        return text.substring(0, end).stripTrailing() + "…";
    }

    private String acct(Actor actor) {
        return actors.isLocal(actor) ? actor.username() : actor.username() + "@" + actor.domain();
    }
}
