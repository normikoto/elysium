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
     * Other servers tell visibilities apart by whether the public collection is in "to", in "cc", or in neither.
     *
     * @param others anyone else to address, such as the author of a parent status
     * @throws IllegalArgumentException if the visibility is direct, because there's no way yet to address it to anyone
     */
    static Audience of(Visibility visibility, Actor author, List<String> others) {
        var followers = List.of(Person.followersOf(author).toString());
        return switch (visibility) {
            case PUBLIC -> new Audience(PUBLIC, concat(followers, others));
            case UNLISTED -> new Audience(followers, concat(PUBLIC, others));
            case PRIVATE -> new Audience(followers, others);
            case DIRECT -> throw new IllegalArgumentException("The status is direct");
        };
    }

    private static List<String> concat(List<String> first, List<String> second) {
        return Stream.concat(first.stream(), second.stream()).toList();
    }
}
