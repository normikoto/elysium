package dev.biribiri.elysium.activitypub.proof;

import dev.biribiri.elysium.activitypub.http.HostHeader;
import dev.biribiri.elysium.activitypub.signature.KeyFetchBudget;
import dev.biribiri.elysium.activitypub.signature.ProofKeys;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.signing.ActorSigningKeys;
import dev.biribiri.elysium.actor.signing.SigningKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Adds an integrity proof (FEP-8b32) to each activity this server sends, signed with its actor's Ed25519 key, so that a
 * server it's forwarded to can check it came from that actor without fetching it, and checks the proofs on activities
 * that are forwarded here.
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

    /**
     * FEP-8b32 says an object shouldn't have more than one proof, and each one can cost a key fetch.
     */
    private static final int MAX_PROOFS_CHECKED = 2;

    private final ActorService actors;
    private final ActorSigningKeys signingKeys;
    private final ProofKeys proofKeys;
    private final JsonMapper json;
    private final Clock clock;

    @Autowired
    ActivityProofs(ActorService actors, ActorSigningKeys signingKeys, ProofKeys proofKeys, JsonMapper json) {
        this(actors, signingKeys, proofKeys, json, Clock.systemUTC());
    }

    ActivityProofs(ActorService actors, ActorSigningKeys signingKeys, ProofKeys proofKeys, JsonMapper json, Clock clock) {
        this.actors = actors;
        this.signingKeys = signingKeys;
        this.proofKeys = proofKeys;
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

    /**
     * Whether the activity has a valid proof made with one of its actor's keys. An activity with no proof, or only proofs
     * that can't be checked, isn't, and the caller has to fall back on something else. This blocks while fetching the
     * actor's key, so it mustn't be called inside a database transaction.
     *
     * @param actor  the activity's {@code actor}
     * @param budget the key fetches that the request's sender is allowed to cause
     */
    public boolean isSignedBy(ObjectNode activity, URI actor, KeyFetchBudget budget) {
        if (!activity.has("proof")) {
            return false;
        }
        return DataIntegrityProofs.candidates(activity, clock.instant()).stream()
                // A key on another server can't be the actor's, so it isn't worth a fetch.
                .filter(candidate -> isOnServerOf(candidate.verificationMethod(), actor))
                .limit(MAX_PROOFS_CHECKED)
                .anyMatch(candidate -> proofKeys.verify(candidate.verificationMethod(), candidate.signature(), budget)
                        .filter(actor::equals)
                        .isPresent());
    }

    private static boolean isOnServerOf(String keyId, URI actor) {
        try {
            var key = new URI(keyId);
            return "https".equalsIgnoreCase(key.getScheme())
                    && key.getHost() != null
                    && actor.getScheme() != null
                    && actor.getHost() != null
                    && HostHeader.value(key.getScheme(), key.getHost(), key.getPort())
                    .equals(HostHeader.value(actor.getScheme(), actor.getHost(), actor.getPort()));
        } catch (URISyntaxException e) {
            return false;
        }
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
