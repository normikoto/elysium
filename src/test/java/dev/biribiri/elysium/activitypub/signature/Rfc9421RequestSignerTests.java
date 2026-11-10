package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.signing.SigningKey;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.HttpHeaders;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Rfc9421RequestSignerTests {

    private static final Instant NOW = Instant.ofEpochSecond(SignatureFixtures.CREATED);
    private static final String KEY_ID = "https://remote.example/users/bob#main-key";
    private static final SigningKey KEY = new SigningKey(KEY_ID, SignatureFixtures.privateKey());
    private static final byte[] BODY = SignatureFixtures.BODY.getBytes(StandardCharsets.UTF_8);
    private static final URI INBOX = URI.create("https://example.test/users/alice/inbox");

    private final Rfc9421RequestSigner signer = new Rfc9421RequestSigner(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void producesTheSameSignatureAsOpenSslForAPost() {
        var headers = signer.sign("POST", INBOX, BODY, KEY);

        assertThat(headers.getFirst("Content-Digest")).isEqualTo("sha-256=:" + SignatureFixtures.BODY_DIGEST + ":");
        assertThat(headers.getFirst("Signature-Input")).isEqualTo(SignatureFixtures.RFC_9421_POST_INPUT);
        assertThat(headers.getFirst("Signature")).isEqualTo("sig1=:" + SignatureFixtures.RFC_9421_POST_SIGNATURE + ":");
    }

    @Test
    void signsAGetWithoutAContentDigest() {
        var headers = signer.sign("GET", URI.create("https://example.test/users/alice?page=1"), new byte[0], KEY);

        assertThat(headers.containsHeader("Content-Digest")).isFalse();
        assertThat(headers.containsHeader("Digest")).isFalse();
        assertThat(headers.containsHeader("Date")).isFalse();
        assertThat(headers.getFirst("Signature-Input")).isEqualTo("sig1=(\"@method\" \"@target-uri\");created=%d;"
                .formatted(SignatureFixtures.CREATED) + "keyid=\"" + KEY_ID + "\";alg=\"rsa-v1_5-sha256\"");
    }

    /**
     * Spring sorts injected signers with this comparator, and cavage has to stay first. The package documentation explains
     * why.
     */
    @Test
    void comesAfterTheCavageSigner() {
        var cavage = new CavageRequestSigner();
        var signers = new ArrayList<RequestSigner>(List.of(signer, cavage));

        AnnotationAwareOrderComparator.sort(signers);

        assertThat(signers).containsExactly(cavage, signer);
    }

    @Test
    void isAcceptedByTheVerifierWithBothSchemes() {
        var signed = signer.sign("POST", INBOX, BODY, KEY);

        var verified = verifier().verify(received("POST", "/users/alice/inbox", BODY, signed), SignatureFixtures.UNLIMITED);

        assertThat(verified.keyId()).isEqualTo(KEY_ID);
    }

    @Test
    void signsADigestOfAnEmptyBodyOnMethodsThatCanHaveOne() {
        var empty = new byte[0];

        var headers = signer.sign("POST", INBOX, empty, KEY);

        assertThat(headers.getFirst("Content-Digest"))
                .isEqualTo("sha-256=:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=:");
        assertThat(verifier().verify(received("POST", "/users/alice/inbox", empty, headers), SignatureFixtures.UNLIMITED)
                .keyId()).isEqualTo(KEY_ID);
    }

    @Test
    void signsTheTargetInTheFormThatIsSent() {
        var headers = signer.sign("GET", URI.create("https://example.test/users/ü?name=ü"), new byte[0], KEY);

        assertThat(verifier().verify(received("GET", "/users/%C3%BC?name=%C3%BC", new byte[0], headers),
                SignatureFixtures.UNLIMITED).keyId()).isEqualTo(KEY_ID);
    }

    @Test
    void signsTheTargetUriAsTheRequestLineAndHostHeaderWillSayIt() {
        var plain = signer.sign("GET", URI.create("https://example.test/"), new byte[0], KEY);

        for (var target : List.of("HTTPS://Example.TEST:443/", "https://example.test", "https://example.test:443")) {
            assertThat(signer.sign("GET", URI.create(target), new byte[0], KEY).getFirst("Signature"))
                    .as(target)
                    .isEqualTo(plain.getFirst("Signature"));
        }
        assertThat(signer.sign("GET", URI.create("https://example.test:8443/"), new byte[0], KEY).getFirst("Signature"))
                .isNotEqualTo(plain.getFirst("Signature"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://my_host.example/inbox", "https://bücher.example/inbox", "mailto:a@example.test", "/inbox"
    })
    void explainsThatItCannotSignAUrlWithoutAValidHost(String target) {
        assertThatThrownBy(() -> signer.sign("POST", URI.create(target), BODY, KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid host");
    }

    /**
     * The changed body fails at its digest, and the rest fail at the signature. The last is the request sent again to
     * another server.
     */
    @ParameterizedTest
    @CsvSource({
            "POST, /users/alice/inbox, other, https://example.test/users/alice/inbox",
            "POST, /users/mallory/inbox, , https://example.test/users/alice/inbox",
            "PUT, /users/alice/inbox, , https://example.test/users/alice/inbox",
            "POST, /users/alice/inbox, , https://other.example/users/alice/inbox"
    })
    void isRejectedIfAnySignedPartChanges(String method, String target, String body, URI signedFor) {
        var received = body == null ? BODY : body.getBytes(StandardCharsets.UTF_8);
        var request = received(method, target, received, signer.sign("POST", signedFor, BODY, KEY));

        assertThatThrownBy(() -> verifier().verify(request, SignatureFixtures.UNLIMITED))
                .isInstanceOf(SignatureVerificationException.class);
    }

    private static HttpRequestData received(String method, String target, byte[] body, HttpHeaders signed) {
        var headers = new HttpHeaders();
        headers.addAll(signed);
        return new HttpRequestData(method, target, headers, body);
    }

    private static SignatureVerifier verifier() {
        var instance = new InstanceProperties(URI.create("https://example.test"));
        var keys = mock(PublicKeyResolver.class);
        when(keys.resolve(any(), any())).thenReturn(new ResolvedKey(
                KEY_ID, URI.create("https://remote.example/users/bob"), SignatureFixtures.publicKey()));
        var schemes = new ArrayList<SignatureScheme>(
                List.of(new CavageSignatureScheme(instance), new Rfc9421SignatureScheme(instance)));
        AnnotationAwareOrderComparator.sort(schemes);
        return new SignatureVerifier(schemes, keys, instance, mock(DomainBlocks.class), mock(ActorService.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
