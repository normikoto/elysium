package dev.biribiri.elysium.content;

import java.util.List;

/**
 * The statuses around one in its thread.
 *
 * @param ancestors   the statuses it replies to, oldest first
 * @param descendants the replies to it and their replies, depth first
 */
public record StatusContext(List<Status> ancestors, List<Status> descendants) {
}
