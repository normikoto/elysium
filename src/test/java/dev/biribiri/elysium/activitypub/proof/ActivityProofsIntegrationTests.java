package dev.biribiri.elysium.activitypub.proof;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ActivityProofsIntegrationTests {

    private static final String AS = "https://www.w3.org/ns/activitystreams";

    @Autowired
    private ActivityProofs proofs;

    @Autowired
    private ActorService actors;

    @Autowired
    private JsonMapper json;

    private Actor alice;

    @BeforeEach
    void createActor() {
        alice = actors.findAnyLocalByUsername("proofalice")
                .orElseGet(() -> actors.createLocalIfAbsent("proofalice", created -> {
                }).orElseThrow());
    }

    @Test
    void signsWithTheActorsEd25519Key() throws Exception {
        var signed = sign(create(alice, "\"" + AS + "\""), alice.id());

        var proof = signed.get("proof");
        assertThat(proof.get("type").stringValue()).isEqualTo("DataIntegrityProof");
        assertThat(proof.get("cryptosuite").stringValue()).isEqualTo("eddsa-jcs-2022");
        assertThat(proof.get("proofPurpose").stringValue()).isEqualTo("assertionMethod");
        assertThat(proof.get("verificationMethod").stringValue()).isEqualTo(alice.uri() + "#ed25519-key");
        assertThat(proof.get("created").stringValue()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ");
        assertThat(proof.get("@context")).isEqualTo(signed.get("@context"));
        assertThat(ProofVerifier.verifies(signed, publicKeyOf(alice))).isTrue();
    }

    @Test
    void addsTheDataIntegrityContextLast() {
        var signed = sign(create(alice, "[\"" + AS + "\", {\"toot\": \"http://joinmastodon.org/ns#\"}]"), alice.id());

        assertThat(json.writeValueAsString(signed.get("@context")))
                .isEqualTo("[\"" + AS + "\",{\"toot\":\"http://joinmastodon.org/ns#\"},\""
                        + ActivityProofs.DATA_INTEGRITY_CONTEXT + "\"]");
    }

    @Test
    void doesNotAddTheDataIntegrityContextTwice() {
        var signed = sign(create(alice, "[\"" + AS + "\", \"" + ActivityProofs.DATA_INTEGRITY_CONTEXT + "\"]"), alice.id());

        assertThat(signed.get("@context").size()).isEqualTo(2);
    }

    @Test
    void anyChangeBreaksTheProof() throws Exception {
        var signed = sign(create(alice, "\"" + AS + "\""), alice.id());

        var changed = signed.deepCopy();
        changed.put("type", "Delete");
        var otherContext = signed.deepCopy();
        otherContext.set("@context", json.readTree("[\"" + AS + "\"]"));

        assertThat(ProofVerifier.verifies(changed, publicKeyOf(alice))).isFalse();
        assertThat(ProofVerifier.verifies(otherContext, publicKeyOf(alice))).isFalse();
    }

    @Test
    void signsAsTheInstanceActorWhenNoAccountSendsIt() throws Exception {
        var instance = actors.instanceActor();
        var body = "{\"@context\":\"" + AS + "\",\"id\":\"https://elysium.example/reports/1\",\"type\":\"Flag\",\"actor\":\""
                + instance.uri() + "\"}";

        var signed = sign(body, null);

        assertThat(signed.get("proof").get("verificationMethod").stringValue()).isEqualTo(instance.uri() + "#ed25519-key");
        assertThat(ProofVerifier.verifies(signed, instance.proofPublicKeyPem())).isTrue();
    }

    @Test
    void leavesTheActivityAsItWasIfTheActorHasNoKey() {
        var body = create(alice, "\"" + AS + "\"");

        assertThat(proofs.sign(body, UUID.randomUUID())).isEqualTo(body);
    }

    private ObjectNode sign(String body, UUID actorId) {
        return (ObjectNode) json.readTree(proofs.sign(body, actorId));
    }

    private static String create(Actor actor, String context) {
        return "{\"@context\":" + context + ",\"id\":\"" + actor.uri() + "/statuses/1#create\",\"type\":\"Create\","
                + "\"actor\":\"" + actor.uri() + "\",\"object\":{\"type\":\"Note\",\"content\":\"<p>Héllo</p>\"}}";
    }

    private String publicKeyOf(Actor actor) {
        return actors.findProofPublicKeyPem(actor).orElseThrow();
    }
}
