package dev.biribiri.elysium.activitypub.vocab;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.content.Visibility;

import java.util.List;
import java.util.stream.Stream;

/**
 * Who an object or activity is addressed to.
 */
record Audience(List<String> to, List<String> cc) {

    private static final List<String> PUBLIC = List.of(ActivityStreams.PUBLIC);

    /**
     * Other servers tell visibilities apart by whether the public collection is in "to", in "cc", or in neither, and a direct
     * status from a followers-only one by whether the followers collection is addressed.
     *
     * @param others anyone else to address, such as the accounts a status mentions and the author of its parent
     */
    static Audience of(Visibility visibility, Actor author, List<String> others) {
        others = others.stream().distinct().toList();
        var followers = List.of(Person.followersOf(author).toString());
        return switch (visibility) {
            case PUBLIC -> new Audience(PUBLIC, concat(followers, others));
            case UNLISTED -> new Audience(followers, concat(PUBLIC, others));
            case PRIVATE -> new Audience(followers, others);
            case DIRECT -> new Audience(others, List.of());
        };
    }

    private static List<String> concat(List<String> first, List<String> second) {
        return Stream.concat(first.stream(), second.stream()).toList();
    }
}
