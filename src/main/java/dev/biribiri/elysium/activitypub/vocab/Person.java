package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.Actor;

import java.net.URI;
import java.util.List;

/**
 * An ActivityPub actor document.
 *
 * @param context   null when this is part of an activity, which has the {@code @context} for both
 * @param suspended true if the actor is suspended. Otherwise this is left out, as other servers treat a missing one as false.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Person(
        @JsonProperty("@context") List<?> context,
        URI id,
        String type,
        String preferredUsername,
        URI url,
        URI inbox,
        URI outbox,
        URI followers,
        URI following,
        Endpoints endpoints,
        PublicKey publicKey,
        Boolean suspended
) {

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
     * @param sharedInbox this server's shared inbox
     */
    public static Person from(Actor actor, URI sharedInbox) {
        return new Person(
                ActivityStreams.ACTOR_CONTEXT,
                actor.uri(),
                "Person",
                actor.username(),
                // Browsers get the profile page from the same URL as the actor document.
                actor.uri(),
                actor.inboxUri(),
                actor.outboxUri(),
                followersOf(actor),
                followingOf(actor),
                new Endpoints(sharedInbox),
                new PublicKey(actor.publicKeyId(), actor.uri(), actor.publicKeyPem()),
                null);
    }

    /**
     * What's served for a suspended actor. It keeps its key, so that other servers can check its signature on the activity
     * saying this, and so that the actor is still there to come back. An actor has no other details to hide yet, but any added
     * later shouldn't be shown here.
     */
    public static Person suspended(Actor actor, URI sharedInbox) {
        var person = from(actor, sharedInbox);
        return new Person(
                ActivityStreams.SUSPENDED_ACTOR_CONTEXT,
                person.id(),
                person.type(),
                person.preferredUsername(),
                person.url(),
                person.inbox(),
                person.outbox(),
                person.followers(),
                person.following(),
                person.endpoints(),
                person.publicKey(),
                true);
    }

    /**
     * This document as part of an activity, which has the {@code @context} for both.
     */
    Person withoutContext() {
        return new Person(
                null, id, type, preferredUsername, url, inbox, outbox, followers, following, endpoints, publicKey, suspended);
    }
}
