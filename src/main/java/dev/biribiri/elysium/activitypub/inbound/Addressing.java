package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.vocab.ActivityStreams;
import tools.jackson.databind.JsonNode;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Who an activity or object is addressed to. Each property can be one value or an array, of IDs or of objects with one, and
 * the public collection is always written out in full, whichever way it was sent. These are whatever the sender says, so
 * nothing here has been checked.
 *
 * <p>{@code bto} and {@code bcc} are only for working out who something was delivered to, and shouldn't be shown to anyone.
 */
record Addressing(Set<String> to, Set<String> cc, Set<String> bto, Set<String> bcc, Set<String> audience) {

    static final Addressing NONE = new Addressing(Set.of(), Set.of(), Set.of(), Set.of(), Set.of());

    Addressing {
        to = Set.copyOf(to);
        cc = Set.copyOf(cc);
        bto = Set.copyOf(bto);
        bcc = Set.copyOf(bcc);
        audience = Set.copyOf(audience);
    }

    /**
     * @param node an activity or object
     */
    static Addressing of(JsonNode node) {
        return new Addressing(
                read(node.path("to")),
                read(node.path("cc")),
                read(node.path("bto")),
                read(node.path("bcc")),
                read(node.path("audience")));
    }

    /**
     * Reads an activity's addressing, along with its embedded {@code object}'s for a {@code Create} or {@code Update}, because
     * some servers only address one of them. Mastodon copies the object's onto its {@code Create}, for example, and others
     * don't. Other types can embed someone else's object, such as an {@code Announce} of another actor's post, whose
     * addressing isn't the activity's.
     *
     * @param activity the activity's JSON
     */
    static Addressing ofActivity(JsonNode activity) {
        var addressing = of(activity);
        var types = ActivityJson.types(activity.path("type"));
        if (!types.contains("Create") && !types.contains("Update")) {
            return addressing;
        }
        var object = activity.path("object");
        if (object.isArray() && object.size() == 1) {
            object = object.path(0);
        }
        return object.isObject() ? addressing.with(of(object)) : addressing;
    }

    /**
     * @return both, in each property
     */
    Addressing with(Addressing other) {
        return new Addressing(
                union(to, other.to),
                union(cc, other.cc),
                union(bto, other.bto),
                union(bcc, other.bcc),
                union(audience, other.audience));
    }

    /**
     * Only {@code to} and {@code cc} count, since those are the ones that other servers use to tell public and unlisted
     * apart.
     */
    boolean isPublic() {
        return to.contains(ActivityStreams.PUBLIC) || cc.contains(ActivityStreams.PUBLIC);
    }

    /**
     * @return every address, from all the properties
     */
    Set<String> all() {
        return Stream.of(to, cc, bto, bcc, audience).flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> read(JsonNode node) {
        return ActivityJson.ids(node).stream().map(ActivityJson::audienceId).collect(Collectors.toSet());
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        return Stream.concat(first.stream(), second.stream()).collect(Collectors.toSet());
    }
}
