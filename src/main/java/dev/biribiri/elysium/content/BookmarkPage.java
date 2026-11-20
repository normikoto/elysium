package dev.biribiri.elysium.content;

import java.util.List;
import java.util.UUID;

/**
 * @param bookmarkIds every bookmark the page read, newest first, which page links are made from, so that paging goes on
 *                    past a page whose statuses the viewer can no longer all see
 * @param statuses    the bookmarked statuses that the viewer can see, in the same order
 */
public record BookmarkPage(List<UUID> bookmarkIds, List<Status> statuses) {
}
