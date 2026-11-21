package dev.biribiri.elysium.activitypub.proof;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.signing.ActorSigningKeys;
import dev.biribiri.elysium.actor.signing.SigningKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Adds an integrity proof (FEP-8b32) to each activity this server sends, signed with its actor's Ed25519 key, so that a
 * server it's forwarded to can check it came from that actor without fetching it.
 *
 * <p>Most activity IDs here are fragments, as Mastodon's are, and FEP-8b32 says objects with fragment IDs shouldn't have
 * proofs. They get one anyway, because the FEP means objects inside another document, and each of these is sent as a
 * document of its own.
 */
@Component
public class ActivityProofs {

    /**
     * The {@code @context} that defines {@code proof}, as FEP-8b32's examples name it. It's protected, so it goes last,
     * where no definition after it can clash with its terms.
     */
    static final String DATA_INTEGRITY_CONTEXT = "https://w3id.org/security/data-integrity/v2";

    private final ActorService actors;
    private final ActorSigningKeys signingKeys;
    private final JsonMapper json;
    private final Clock clock;

    @Autowired
    ActivityProofs(ActorService actors, ActorSigningKeys signingKeys, JsonMapper json) {
        this(actors, signingKeys, json, Clock.systemUTC());
    }

    ActivityProofs(ActorService actors, ActorSigningKeys signingKeys, JsonMapper json, Clock clock) {
        this.actors = actors;
        this.signingKeys = signingKeys;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param body    an activity's JSON, without a proof
     * @param actorId the local actor who sends it, or null for the instance actor
     * @return the activity with its proof, or as it was if the actor's keys are gone, as they are once it's been deleted
     *         long enough
     */
    public String sign(String body, UUID actorId) {
        var key = keyFor(actorId);
        if (key.isEmpty()) {
            return body;
        }
        var activity = (ObjectNode) json.readTree(body);
        activity.set("@context", withDataIntegrity(activity.get("@context")));
        return json.writeValueAsString(DataIntegrityProofs.addProof(activity, key.get(), clock.instant()));
    }

    private Optional<SigningKey> keyFor(UUID actorId) {
        if (actorId == null) {
            return Optional.of(signingKeys.proofKeyForInstance());
        }
        return actors.findById(actorId).flatMap(signingKeys::proofKeyFor);
    }

    private ArrayNode withDataIntegrity(JsonNode context) {
        var array = json.createArrayNode();
        if (context instanceof ArrayNode existing) {
            array.addAll(existing);
        } else if (context != null) {
            array.add(context);
        }
        if (!array.valueStream().anyMatch(entry -> entry.isString() && entry.stringValue().equals(DATA_INTEGRITY_CONTEXT))) {
            array.add(DATA_INTEGRITY_CONTEXT);
        }
        return array;
    }
}
