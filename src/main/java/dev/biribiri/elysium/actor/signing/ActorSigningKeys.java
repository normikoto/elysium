package dev.biribiri.elysium.actor.signing;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.internal.ActorRepository;
import dev.biribiri.elysium.actor.internal.InstanceActorKeys;
import dev.biribiri.elysium.actor.internal.RsaKeys;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ActorSigningKeys {

    private final ActorRepository actors;
    private final ActorService actorService;
    private final InstanceActorKeys instanceActorKeys;

    ActorSigningKeys(ActorRepository actors, ActorService actorService, InstanceActorKeys instanceActorKeys) {
        this.actors = actors;
        this.actorService = actorService;
        this.instanceActorKeys = instanceActorKeys;
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

    /**
     * Gets the key to sign requests with when no user is behind them, such as fetching another server's public key.
     */
    public SigningKey forInstance() {
        return new SigningKey(
                actorService.instanceActor().publicKeyId(),
                RsaKeys.parsePrivateKey(instanceActorKeys.get().privateKeyPem()));
    }
}
