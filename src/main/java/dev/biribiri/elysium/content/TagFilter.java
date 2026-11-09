package dev.biribiri.elysium.content;

import java.util.Set;

/**
 * Which hashtags a listed status has to have, by their normalized names.
 *
 * @param any  it has at least one of these, which can't be empty
 * @param all  it has every one of these
 * @param none it has none of these
 */
public record TagFilter(Set<String> any, Set<String> all, Set<String> none) {

    public TagFilter {
        any = Set.copyOf(any);
        all = Set.copyOf(all);
        none = Set.copyOf(none);
        if (any.isEmpty()) {
            throw new IllegalArgumentException("A tag filter needs at least one tag to look for");
        }
    }

    public static TagFilter of(String name) {
        return new TagFilter(Set.of(name), Set.of(), Set.of());
    }
}
