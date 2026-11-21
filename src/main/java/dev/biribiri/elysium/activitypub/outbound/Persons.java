package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.vocab.Person;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.Profile;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.content.CustomEmojis;
import org.springframework.stereotype.Service;

/**
 * Builds a local actor's document, so that one that's fetched matches one that's delivered.
 */
@Service
public class Persons {

    private final ActorService actors;
    private final ProfileService profiles;
    private final CustomEmojis emojis;
    private final InstanceProperties instance;

    Persons(ActorService actors, ProfileService profiles, CustomEmojis emojis, InstanceProperties instance) {
        this.actors = actors;
        this.profiles = profiles;
        this.emojis = emojis;
        this.instance = instance;
    }

    public Person of(Actor actor) {
        return of(actor, profiles.find(actor.id()));
    }

    public Person of(Actor actor, Profile profile) {
        return Person.from(actor, profile, emojis.findLocalIn(profile.texts()).values(), proofKey(actor), instance);
    }

    /**
     * @see Person#suspended
     */
    public Person suspended(Actor actor) {
        return Person.suspended(actor, proofKey(actor), instance);
    }

    /**
     * @see Person#minimal
     */
    public Person minimal(Actor actor) {
        return Person.minimal(actor, proofKey(actor), instance);
    }

    private String proofKey(Actor actor) {
        return actors.findProofPublicKeyPem(actor).orElse(null);
    }
}
