package dev.biribiri.elysium.content;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What to set on a filter. Null means to keep what it has, or for a new filter, to use the default, where there is one.
 *
 * @param expiry   null to keep the expiry
 * @param keywords keywords to add, change, or remove, in order
 */
public record PostFilterChange(
        String title,
        Set<PostFilter.Context> contexts,
        PostFilter.Action action,
        Expiry expiry,
        List<KeywordEdit> keywords) {

    public PostFilterChange {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    /**
     * @param at when it expires, or null for never
     */
    public record Expiry(Instant at) {
    }

    /**
     * @param id        the keyword to change or remove, or null to add one
     * @param keyword   null to keep it
     * @param wholeWord null to keep it, or for a new keyword, to use the default
     * @param remove    whether to remove the keyword
     */
    public record KeywordEdit(UUID id, String keyword, Boolean wholeWord, boolean remove) {
    }
}
