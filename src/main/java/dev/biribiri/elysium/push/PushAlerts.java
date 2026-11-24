package dev.biribiri.elysium.push;

import java.util.List;

/**
 * The notification types a subscription can choose. Others, such as {@code move}, are never pushed, since Mastodon's
 * clients have no setting for them.
 */
public final class PushAlerts {

    /**
     * Mastodon's, in the order it lists them, and then Pleroma's emoji reactions. {@code admin.sign_up} is accepted
     * though nothing sends it yet, so that clients see the setting they chose.
     */
    public static final List<String> ALL = List.of(
            "mention", "status", "reblog", "follow", "follow_request", "favourite", "poll", "update", "quote",
            "quoted_update", "admin.sign_up", "admin.report", "pleroma:emoji_reaction");

    private PushAlerts() {
    }
}
