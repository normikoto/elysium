package dev.biribiri.elysium.content;

import java.util.List;
import java.util.UUID;

/**
 * @param likeIds  every favourite the page read, newest first, which page links are made from, so that paging goes on
 *                 past a page whose statuses the viewer can no longer all see
 * @param statuses the favourited statuses that the viewer can see, in the same order
 */
public record FavouritePage(List<UUID> likeIds, List<Status> statuses) {
}
