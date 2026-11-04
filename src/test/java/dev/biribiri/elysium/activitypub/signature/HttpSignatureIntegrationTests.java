package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.signing.ActorSigningKeys;
import dev.biribiri.elysium.actor.signing.SigningKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Signs a request with a real local actor's key from the database, and checks it with the application's own beans. The
 * base URL gives this class its own context, and so its own database.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.instance.base-url=https://signatures.example")
class HttpSignatureIntegrationTests {

    private static final byte[] BODY = "{\"type\":\"Create\"}".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private ActorService actors;

    @Autowired
    private ActorSigningKeys signingKeys;

    @Autowired
    private RequestSigner signer;

    @Autowired
    private SignatureVerifier verifier;

    @Test
    void verifiesARequestSignedWithALocalActorsKey() {
        var alice = actors.createLocalIfAbsent("alice", actor -> {
        }).orElseThrow();
        var key = signingKeys.forActor(alice).orElseThrow();

        var verified = verifier.verify(signed("/users/bob/inbox", BODY, key));

        assertThat(verified).isEqualTo(new VerifiedSignature(alice.publicKeyId(), alice.uri()));
    }

    @Test
    void rejectsATamperedRequest() {
        var carol = actors.createLocalIfAbsent("carol", actor -> {
        }).orElseThrow();
        var key = signingKeys.forActor(carol).orElseThrow();
        var signed = signed("/users/bob/inbox", BODY, key);
        var tampered = new HttpRequestData("POST", "/users/mallory/inbox", signed.headers(), signed.body());

        assertThatThrownBy(() -> verifier.verify(tampered))
                .isInstanceOfSatisfying(SignatureVerificationException.class,
                        e -> assertThat(e.failure()).isEqualTo(SignatureVerificationException.Failure.REJECTED));
    }

    @Test
    void rejectsOneActorSigningWithAnothersKeyId() {
        var dave = actors.createLocalIfAbsent("dave", actor -> {
        }).orElseThrow();
        var erin = actors.createLocalIfAbsent("erin", actor -> {
        }).orElseThrow();
        var forged = new SigningKey(erin.publicKeyId(), signingKeys.forActor(dave).orElseThrow().privateKey());

        assertThatThrownBy(() -> verifier.verify(signed("/users/bob/inbox", BODY, forged)))
                .isInstanceOfSatisfying(SignatureVerificationException.class,
                        e -> assertThat(e.failure()).isEqualTo(SignatureVerificationException.Failure.REJECTED));
    }

    /**
     * The other tests use made-up clients and resolvers to reach a loopback server, so this checks what the application
     * actually runs with.
     */
    @Test
    void neverConnectsToAKeyIdOnAPrivateAddress() throws Exception {
        var frank = actors.createLocalIfAbsent("frank", actor -> {
        }).orElseThrow();
        var privateKey = signingKeys.forActor(frank).orElseThrow().privateKey();

        try (var server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(500);
            var keyId = "https://127.0.0.1:" + server.getLocalPort() + "/users/frank#main-key";

            var request = signed("/users/bob/inbox", BODY, new SigningKey(keyId, privateKey));

            assertThatThrownBy(() -> verifier.verify(request))
                    .isInstanceOfSatisfying(SignatureVerificationException.class,
                            e -> assertThat(e.failure()).isEqualTo(SignatureVerificationException.Failure.REJECTED));
            assertThatThrownBy(server::accept).isInstanceOf(SocketTimeoutException.class);
        }
    }

    @Test
    void hasNoSigningKeyForAnActorThatIsNotLocal() {
        var remote = new Actor(
                UUID.randomUUID(), URI.create("https://remote.example/users/bob"), "bob", "remote.example",
                URI.create("https://remote.example/users/bob/inbox"),
                URI.create("https://remote.example/users/bob/outbox"),
                null, "unused", Instant.EPOCH);

        assertThat(signingKeys.forActor(remote)).isEmpty();
    }

    private HttpRequestData signed(String path, byte[] body, SigningKey key) {
        var headers = new HttpHeaders();
        headers.addAll(signer.sign("POST", URI.create("https://signatures.example" + path), body, key));
        return new HttpRequestData("POST", path, headers, body);
    }
}
