package dev.biribiri.elysium.actor.signing;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.internal.ActorRepository;
import dev.biribiri.elysium.actor.internal.RsaKeys;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ActorSigningKeys {

    private final ActorRepository actors;

    ActorSigningKeys(ActorRepository actors) {
        this.actors = actors;
    }

    /**
     * Finds the key to sign this actor's requests with.
     *
     * @return the key, or empty if the actor isn't local
     */
    public Optional<SigningKey> forActor(Actor actor) {
        return actors.findPrivateKeyPem(actor.id())
                .map(pem -> new SigningKey(actor.publicKeyId(), RsaKeys.parsePrivateKey(pem)));
    }
}
