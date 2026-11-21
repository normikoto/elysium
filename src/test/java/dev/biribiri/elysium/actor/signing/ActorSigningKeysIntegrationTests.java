package dev.biribiri.elysium.actor.signing;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.ActorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ActorSigningKeysIntegrationTests {

    @Autowired
    private ActorSigningKeys signingKeys;

    @Autowired
    private ActorService actors;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void aNewAccountSignsProofsWithItsOwnEd25519Key() throws Exception {
        var actor = actors.createLocalIfAbsent("proofsigner", created -> {
        }).orElseThrow();

        var key = signingKeys.proofKeyFor(actor).orElseThrow();

        assertThat(key.keyId()).isEqualTo(actor.uri() + "#ed25519-key");
        var publicKeyPem = jdbc.sql("SELECT ed25519_public_key_pem FROM local_account WHERE actor_id = :id")
                .param("id", actor.id())
                .query(String.class)
                .single();
        assertThat(verifies(key, publicKey(publicKeyPem))).isTrue();
        assertThat(signingKeys.forActor(actor).orElseThrow().privateKey().getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    void theInstanceActorSignsProofsWithItsOwnEd25519Key() throws Exception {
        var key = signingKeys.proofKeyForInstance();

        assertThat(key.keyId()).isEqualTo(actors.instanceActor().uri() + "#ed25519-key");
        var publicKeyPem = jdbc.sql("SELECT ed25519_public_key_pem FROM instance_actor")
                .query(String.class)
                .single();
        assertThat(verifies(key, publicKey(publicKeyPem))).isTrue();
    }

    private static boolean verifies(SigningKey key, PublicKey publicKey) throws Exception {
        var data = "data".getBytes(StandardCharsets.UTF_8);
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(key.privateKey());
        signer.update(data);
        var signature = signer.sign();
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(data);
        return verifier.verify(signature);
    }

    private static PublicKey publicKey(String pem) throws Exception {
        var der = Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", ""));
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }
}
