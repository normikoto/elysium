package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.signing.SigningKey;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CavageRequestSignerTests {

    /** The third of the month, so the day needs padding, and the same time as {@link SignatureFixtures#DATE}. */
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private static final String KEY_ID = "https://remote.example/users/bob#main-key";
    private static final SigningKey KEY = new SigningKey(KEY_ID, SignatureFixtures.privateKey());
    private static final byte[] BODY = SignatureFixtures.BODY.getBytes(StandardCharsets.UTF_8);
    private static final URI INBOX = URI.create("https://example.test/users/alice/inbox");

    private final CavageRequestSigner signer = new CavageRequestSigner(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void producesTheSameSignatureAsOpenSslForAPost() {
        var headers = signer.sign("POST", INBOX, BODY, KEY);

        assertThat(headers.getFirst(HttpHeaders.DATE)).isEqualTo(SignatureFixtures.DATE);
        assertThat(headers.getFirst("Digest")).isEqualTo("SHA-256=" + SignatureFixtures.BODY_DIGEST);
        assertThat(headers.getFirst("Signature")).isEqualTo(
                "keyId=\"%s\",algorithm=\"rsa-sha256\",headers=\"(request-target) host date digest\",signature=\"%s\""
                        .formatted(KEY_ID, SignatureFixtures.POST_SIGNATURE));
    }

    @Test
    void producesTheSameSignatureAsOpenSslForAGetWithAQuery() {
        var headers = signer.sign("GET", URI.create("https://example.test/users/alice?page=1"), new byte[0], KEY);

        assertThat(headers.containsHeader("Digest")).isFalse();
        assertThat(headers.getFirst("Signature")).isEqualTo(
                "keyId=\"%s\",algorithm=\"rsa-sha256\",headers=\"(request-target) host date\",signature=\"%s\""
                        .formatted(KEY_ID, SignatureFixtures.GET_SIGNATURE));
    }

    @Test
    void padsSingleDigitDaysInTheDate() {
        assertThat(signer.sign("GET", INBOX, new byte[0], KEY).getFirst(HttpHeaders.DATE))
                .isEqualTo("Tue, 03 Nov 2026 12:00:00 GMT");
    }

    @Test
    void signsWhatTheHostHeaderWillSay() {
        var withPort = signer.sign("GET", URI.create("https://Example.TEST:8443/a"), new byte[0], KEY);
        var defaultPort = signer.sign("GET", URI.create("https://example.test:443/a"), new byte[0], KEY);
        var plain = signer.sign("GET", URI.create("https://example.test/a"), new byte[0], KEY);

        assertThat(defaultPort.getFirst("Signature")).isEqualTo(plain.getFirst("Signature"));
        assertThat(withPort.getFirst("Signature")).isNotEqualTo(plain.getFirst("Signature"));
    }

    @Test
    void signsAMissingPathAsTheRoot() {
        var withoutPath = signer.sign("GET", URI.create("https://example.test"), new byte[0], KEY);
        var withPath = signer.sign("GET", URI.create("https://example.test/"), new byte[0], KEY);

        assertThat(withoutPath.getFirst("Signature")).isEqualTo(withPath.getFirst("Signature"));
    }

    @Test
    void isAcceptedByTheVerifier() {
        var signed = signer.sign("POST", INBOX, BODY, KEY);

        var verified = verifier().verify(received("POST", "/users/alice/inbox", BODY, signed));

        assertThat(verified.keyId()).isEqualTo(KEY_ID);
    }

    @Test
    void isRejectedIfTheBodyChanges() {
        var request = received("POST", "/users/alice/inbox", "other".getBytes(StandardCharsets.UTF_8),
                signer.sign("POST", INBOX, BODY, KEY));

        assertThatThrownBy(() -> verifier().verify(request)).isInstanceOf(SignatureVerificationException.class);
    }

    @Test
    void isRejectedIfTheTargetChanges() {
        var request = received("POST", "/users/mallory/inbox", BODY, signer.sign("POST", INBOX, BODY, KEY));

        assertThatThrownBy(() -> verifier().verify(request)).isInstanceOf(SignatureVerificationException.class);
    }

    @Test
    void isRejectedIfTheMethodChanges() {
        var request = received("PUT", "/users/alice/inbox", BODY, signer.sign("POST", INBOX, BODY, KEY));

        assertThatThrownBy(() -> verifier().verify(request)).isInstanceOf(SignatureVerificationException.class);
    }

    @Test
    void isRejectedByAServerItWasNotSignedFor() {
        var request = received("POST", "/users/alice/inbox", BODY,
                signer.sign("POST", URI.create("https://other.example/users/alice/inbox"), BODY, KEY));

        assertThatThrownBy(() -> verifier().verify(request)).isInstanceOf(SignatureVerificationException.class);
    }

    private static HttpRequestData received(String method, String target, byte[] body, HttpHeaders signed) {
        var headers = new HttpHeaders();
        headers.addAll(signed);
        return new HttpRequestData(method, target, headers, body);
    }

    private static SignatureVerifier verifier() {
        var instance = new InstanceProperties(URI.create("https://example.test"));
        var keys = mock(PublicKeyResolver.class);
        when(keys.resolve(any())).thenReturn(new ResolvedKey(
                KEY_ID, URI.create("https://remote.example/users/bob"), SignatureFixtures.publicKey()));
        return new SignatureVerifier(
                List.of(new CavageSignatureScheme(instance)), keys, instance, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
