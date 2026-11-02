package dev.biribiri.elysium.actor;

import dev.biribiri.elysium.actor.internal.ActorRepository;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Optional;

@Service
public class ActorService {

    private final ActorRepository actors;
    private final InstanceProperties instance;

    ActorService(ActorRepository actors, InstanceProperties instance) {
        this.actors = actors;
        this.instance = instance;
    }

    /**
     * Finds a local actor. Usernames are matched case-insensitively.
     */
    public Optional<Actor> findLocalByUsername(String username) {
        if (!Actor.isValidLocalUsername(username)) {
            return Optional.empty();
        }
        return actors.findLocal(username, instance.domain());
    }

    public Optional<Actor> findLocalByUri(URI uri) {
        return actors.findLocalByUri(uri);
    }
}
