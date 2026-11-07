package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.Profile;
import dev.biribiri.elysium.actor.ProfileImage;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * An ActivityPub actor document.
 *
 * @param context    null when this is part of an activity, which has the {@code @context} for both
 * @param name       the display name, left out if there isn't one
 * @param summary    the bio as HTML, left out if there isn't one
 * @param attachment the profile's fields, as Mastodon sends them
 * @param updated    when the profile last changed, which tells a server that gets two {@code Update}s which is newer
 * @param suspended  true if the actor is suspended. Otherwise this is left out, as other servers treat a missing one as false.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Person(
        @JsonProperty("@context") List<?> context,
        URI id,
        String type,
        String preferredUsername,
        String name,
        String summary,
        URI url,
        URI inbox,
        URI outbox,
        URI followers,
        URI following,
        Endpoints endpoints,
        PublicKey publicKey,
        Image icon,
        Image image,
        List<PropertyValue> attachment,
        String published,
        String updated,
        Boolean suspended
) {

    /**
     * A profile image. They're always WebP, since every upload is re-encoded.
     */
    public record Image(String type, String mediaType, URI url) {

        static Image of(ProfileImage image, InstanceProperties instance) {
            return image == null ? null : new Image("Image", "image/webp", instance.resolve(image.path()));
        }
    }

    /**
     * @param value HTML
     */
    public record PropertyValue(String type, String name, String value) {
    }

    /**
     * @return the followers collection of a local actor
     */
    public static URI followersOf(Actor actor) {
        return URI.create(actor.uri() + "/followers");
    }

    /**
     * @return the following collection of a local actor
     */
    public static URI followingOf(Actor actor) {
        return URI.create(actor.uri() + "/following");
    }

    public static Person from(Actor actor, Profile profile, InstanceProperties instance) {
        return new Person(
                ActivityStreams.PERSON_CONTEXT,
                actor.uri(),
                "Person",
                actor.username(),
                profile.displayName().isEmpty() ? null : profile.displayName(),
                profile.note().isEmpty() ? null : profile.noteHtml(),
                // Browsers get the profile page from the same URL as the actor document.
                actor.uri(),
                actor.inboxUri(),
                actor.outboxUri(),
                followersOf(actor),
                followingOf(actor),
                new Endpoints(instance.sharedInboxUri()),
                new PublicKey(actor.publicKeyId(), actor.uri(), actor.publicKeyPem()),
                Image.of(profile.avatar(), instance),
                Image.of(profile.header(), instance),
                profile.fields().isEmpty()
                        ? null
                        : profile.fields().stream()
                        .map(field -> new PropertyValue("PropertyValue", field.name(), field.valueHtml()))
                        .toList(),
                time(actor.createdAt()),
                time(profile.updatedAt()),
                null);
    }

    /**
     * What's served for a suspended actor. It keeps its key, so that other servers can check its signature on the activity
     * saying this, and so that the actor is still there to come back. Its profile isn't shown, as Mastodon does.
     */
    public static Person suspended(Actor actor, InstanceProperties instance) {
        return new Person(
                ActivityStreams.SUSPENDED_ACTOR_CONTEXT,
                actor.uri(),
                "Person",
                actor.username(),
                null,
                null,
                actor.uri(),
                actor.inboxUri(),
                actor.outboxUri(),
                followersOf(actor),
                followingOf(actor),
                new Endpoints(instance.sharedInboxUri()),
                new PublicKey(actor.publicKeyId(), actor.uri(), actor.publicKeyPem()),
                null,
                null,
                null,
                time(actor.createdAt()),
                null,
                true);
    }

    /**
     * Whole seconds, as {@link Note} has, since some servers can't read fractions.
     */
    private static String time(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * This document as part of an activity, which has the {@code @context} for both.
     */
    Person withoutContext() {
        return new Person(
                null, id, type, preferredUsername, name, summary, url, inbox, outbox, followers, following, endpoints,
                publicKey, icon, image, attachment, published, updated, suspended);
    }
}
