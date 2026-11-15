package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.vocab.Person;
import dev.biribiri.elysium.actor.Actor;
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

    private final ProfileService profiles;
    private final CustomEmojis emojis;
    private final InstanceProperties instance;

    Persons(ProfileService profiles, CustomEmojis emojis, InstanceProperties instance) {
        this.profiles = profiles;
        this.emojis = emojis;
        this.instance = instance;
    }

    public Person of(Actor actor) {
        return of(actor, profiles.find(actor.id()));
    }

    public Person of(Actor actor, Profile profile) {
        return Person.from(actor, profile, emojis.findLocalIn(profile.texts()).values(), instance);
    }
}
