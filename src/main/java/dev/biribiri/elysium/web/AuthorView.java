package dev.biribiri.elysium.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.Profile;

/**
 * Who wrote a status, as its card shows them.
 *
 * @param displayName the profile's name, or the username if it has none
 * @param avatar      the avatar, or null to show {@link #initial} instead
 */
record AuthorView(String displayName, String avatar) {

    static AuthorView of(Actor actor) {
        return new AuthorView(actor.username(), null);
    }

    /**
     * An avatar that's only linked to is left out, so that a visitor's browser doesn't fetch from another server without
     * being asked, as with attachments.
     */
    AuthorView withProfile(Profile profile, InstanceProperties instance) {
        var name = profile.displayName().isBlank() ? displayName : profile.displayName();
        var image = profile.avatar() == null || profile.avatar().path() == null ? null
                : instance.resolve(profile.avatar().path()).toString();
        return new AuthorView(name, image);
    }

    public String initial() {
        return displayName.isEmpty() ? "" : Character.toString(displayName.codePointAt(0)).toUpperCase();
    }
}
