package dev.biribiri.elysium.content;

import java.util.List;
import java.util.UUID;

/**
 * Other servers' custom emoji were stored, or used, with no copy here, to be downloaded.
 */
public record RemoteEmojiAdded(List<UUID> emojiIds) {

    public RemoteEmojiAdded {
        emojiIds = List.copyOf(emojiIds);
    }
}
