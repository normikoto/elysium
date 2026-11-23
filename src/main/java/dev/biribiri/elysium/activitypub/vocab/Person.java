package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.Profile;
import dev.biribiri.elysium.actor.ProfileImage;

import dev.biribiri.elysium.content.CustomEmoji;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * An ActivityPub actor document.
 *
 * @param context    null when this is part of an activity, which has the {@code @context} for both
 * @param type       {@code Service} for a bot, which is what Mastodon sends and reads as one, and {@code Person} otherwise
 * @param name       the display name, left out if there isn't one
 * @param summary    the bio as HTML, left out if there isn't one
 * @param attachment the profile's fields, as Mastodon sends them
 * @param tag        the hashtags in the bio, and the custom emoji in the profile, as Mastodon sends them, left out if there
 *                   are none
 * @param assertionMethod the Ed25519 key that checks the actor's integrity proofs, as FEP-521a has it, or left out if the
 *                   actor has none
 * @param featured   the collection of pinned statuses, left out while the actor is suspended, since it can't be read then
 * @param manuallyApprovesFollowers whether requests to follow the actor wait for it to approve them, left out of a
 *                   {@linkplain #minimal minimal} document, since a false one would say the actor isn't locked
 * @param indexable  whether any server's search may find the actor's public statuses, left out of a minimal document
 * @param isCat      Misskey's flag for an account that's a cat, always sent as Misskey sends it, but left out of a minimal
 *                   document
 * @param updated    when the profile last changed, which tells a server that gets two {@code Update}s which is newer
 * @param alsoKnownAs the accounts that can move to this one, left out if there are none
 * @param movedTo    the account this one has moved to, left out if it hasn't. The account keeps working here, but other
 *                   servers treat it as moved, which is why FEP-7628's {@code copiedTo} isn't sent instead: they don't
 *                   read it.
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
        URI featured,
        Endpoints endpoints,
        PublicKey publicKey,
        List<Multikey> assertionMethod,
        Image icon,
        Image image,
        List<PropertyValue> attachment,
        List<Tag> tag,
        Boolean manuallyApprovesFollowers,
        Boolean indexable,
        Boolean isCat,
        String published,
        String updated,
        List<URI> alsoKnownAs,
        URI movedTo,
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

    /**
     * @return the collection of a local actor's pinned statuses, at the path Mastodon uses
     */
    public static URI featuredOf(Actor actor) {
        return URI.create(actor.uri() + "/collections/featured");
    }

    /**
     * @param emojis       this server's custom emoji that the profile names
     * @param proofKeyPem  the actor's Ed25519 public key, or null if it has none
     */
    public static Person from(
            Actor actor, Profile profile, Collection<CustomEmoji> emojis, String proofKeyPem, InstanceProperties instance) {
        var tags = new ArrayList<Tag>(Hashtag.of(profile.hashtags(), instance));
        tags.addAll(Emoji.of(emojis, instance));
        return new Person(
                ActivityStreams.PERSON_CONTEXT,
                actor.uri(),
                type(profile.bot()),
                actor.username(),
                profile.displayName().isEmpty() ? null : profile.displayName(),
                profile.note().isEmpty() ? null : profile.noteHtml(instance.baseUrl()),
                // Browsers get the profile page from the same URL as the actor document.
                actor.uri(),
                actor.inboxUri(),
                actor.outboxUri(),
                followersOf(actor),
                followingOf(actor),
                featuredOf(actor),
                new Endpoints(instance.sharedInboxUri()),
                new PublicKey(actor.publicKeyId(), actor.uri(), actor.publicKeyPem()),
                assertionMethod(actor, proofKeyPem),
                Image.of(profile.avatar(), instance),
                Image.of(profile.header(), instance),
                profile.fields().isEmpty()
                        ? null
                        : profile.fields().stream()
                        .map(field -> new PropertyValue("PropertyValue", field.name(), field.valueHtml()))
                        .toList(),
                tags.isEmpty() ? null : List.copyOf(tags),
                profile.manuallyApprovesFollowers(),
                profile.indexable(),
                profile.cat(),
                ActivityStreams.time(actor.createdAt()),
                ActivityStreams.time(profile.updatedAt()),
                profile.alsoKnownAs().isEmpty() ? null : profile.alsoKnownAs(),
                profile.movedTo(),
                null);
    }

    /**
     * What's served for a suspended actor. It keeps its key, so that other servers can check its signature on the activity
     * saying this, and so that the actor is still there to come back. Its profile isn't shown, as Mastodon does.
     */
    public static Person suspended(Actor actor, String proofKeyPem, InstanceProperties instance) {
        return from(actor, Profile.EMPTY, List.of(), proofKeyPem, instance).with(ActivityStreams.SUSPENDED_ACTOR_CONTEXT, null, true);
    }

    /**
     * What's served to an unsigned request when signed fetches are required. It keeps the key, because some servers fetch
     * a key without signing, and this server's deliveries are only accepted once the key can be read. That goes for the
     * Ed25519 key too, which a server needs to check an activity's integrity proof. Otherwise it's what Mastodon serves
     * then, with the collections' URIs, which say nothing that the actor's URI doesn't. It keeps the type too, since a
     * server that reads this first would otherwise take a bot for a person until it fetches the actor again.
     *
     * @param bot whether the actor's profile says it's automated, which isn't shown while it's suspended, as the rest of
     *            its profile isn't
     */
    public static Person minimal(Actor actor, boolean bot, String proofKeyPem, InstanceProperties instance) {
        return new Person(
                actor.isSuspended() ? ActivityStreams.SUSPENDED_ACTOR_CONTEXT : ActivityStreams.ACTOR_CONTEXT,
                actor.uri(),
                type(bot && !actor.isSuspended()),
                actor.username(),
                null,
                null,
                null,
                actor.inboxUri(),
                actor.outboxUri(),
                followersOf(actor),
                followingOf(actor),
                null,
                new Endpoints(instance.sharedInboxUri()),
                new PublicKey(actor.publicKeyId(), actor.uri(), actor.publicKeyPem()),
                assertionMethod(actor, proofKeyPem),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                actor.isSuspended() ? true : null);
    }

    private static String type(boolean bot) {
        return bot ? "Service" : "Person";
    }

    private static List<Multikey> assertionMethod(Actor actor, String proofKeyPem) {
        return proofKeyPem == null ? null : List.of(Multikey.ed25519(actor.proofKeyId(), actor.uri(), proofKeyPem));
    }

    /**
     * This document as part of an activity, which has the {@code @context} for both.
     */
    Person withoutContext() {
        return with(null, featured, suspended);
    }

    private Person with(List<?> context, URI featured, Boolean suspended) {
        return new Person(
                context, id, type, preferredUsername, name, summary, url, inbox, outbox, followers, following, featured,
                endpoints,
                publicKey, assertionMethod, icon, image, attachment, tag, manuallyApprovesFollowers, indexable, isCat,
                published, updated, alsoKnownAs, movedTo, suspended);
    }
}
