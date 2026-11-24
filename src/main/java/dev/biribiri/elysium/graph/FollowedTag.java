package dev.biribiri.elysium.graph;

import java.util.UUID;

/**
 * @param id   the follow's ID, which pages a list of followed tags
 * @param name the tag's normalized name
 */
public record FollowedTag(UUID id, String name) {
}
