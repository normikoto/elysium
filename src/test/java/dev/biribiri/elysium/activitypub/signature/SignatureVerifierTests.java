package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException.Failure;
import dev.biribiri.elysium.actor.InstanceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Uses a made-up signature format throughout, which shows that the verifier doesn't depend on the format.
 */
class SignatureVerifierTests {

    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private static final String KEY_ID = "https://remote.example/users/bob#main-key";
    private static final URI OWNER = URI.create("https://remote.example/users/bob");
    private static final byte[] BASE = "signed text".getBytes(StandardCharsets.UTF_8);
    private static final HttpRequestData REQUEST =
            new HttpRequestData("POST", "/inbox", new HttpHeaders(), new byte[0]);
    private static final InstanceProperties INSTANCE = new InstanceProperties(URI.create("https://example.test"));

    private final PublicKeyResolver keys = mock();
    private final ResolvedKey key = new ResolvedKey(KEY_ID, OWNER, SignatureFixtures.publicKey());

    @BeforeEach
    void resolvesTheKey() {
        when(keys.resolve(any())).thenReturn(key);
    }

    @Test
    void acceptsAValidSignature() {
        assertThat(verifier(scheme(claim(NOW, null))).verify(REQUEST)).isEqualTo(new VerifiedSignature(KEY_ID, OWNER));
    }

    @Test
    void usesTheFirstSchemeThatApplies() {
        var skipped = new FakeScheme(false, invalidClaim(NOW));
        var preferred = scheme(claim(NOW, null));
        var fallback = new FakeScheme(true, invalidClaim(NOW));

        assertThat(verifier(skipped, preferred, fallback).verify(REQUEST).keyId()).isEqualTo(KEY_ID);
    }

    @Test
    void failsIfNoSchemeApplies() {
        assertFails(verifier(new FakeScheme(false, claim(NOW, null))), Failure.NO_SUPPORTED_SIGNATURE);
    }

    @Test
    void passesOnAFailureFromTheScheme() {
        var malformed = new SignatureScheme() {
            @Override
            public boolean appliesTo(HttpRequestData request) {
                return true;
            }

            @Override
            public SignatureClaim parse(HttpRequestData request) {
                throw new SignatureVerificationException(Failure.MALFORMED, "bad");
            }
        };

        assertFails(verifier(malformed), Failure.MALFORMED);
        verify(keys, never()).resolve(any());
    }

    @Test
    void acceptsAsFarAheadAsTheClockSkewAllows() {
        assertThat(verifier(scheme(claim(NOW.plus(Duration.ofHours(1)), null))).verify(REQUEST)).isNotNull();
    }

    @Test
    void rejectsFurtherAheadThanTheClockSkewAllows() {
        var claim = claim(NOW.plus(Duration.ofHours(1)).plusSeconds(1), null);

        assertFails(verifier(scheme(claim)), Failure.REJECTED);
        verify(keys, never()).resolve(any());
    }

    @Test
    void acceptsUntilTheDefaultLifetimeAndClockSkewEnd() {
        var created = NOW.minus(Duration.ofMinutes(5)).minus(Duration.ofHours(1));

        assertThat(verifier(scheme(claim(created, null))).verify(REQUEST)).isNotNull();
    }

    @Test
    void rejectsAfterTheDefaultLifetimeAndClockSkewEnd() {
        var created = NOW.minus(Duration.ofMinutes(5)).minus(Duration.ofHours(1)).minusSeconds(1);

        assertFails(verifier(scheme(claim(created, null))), Failure.REJECTED);
        verify(keys, never()).resolve(any());
    }

    @Test
    void acceptsUntilACoveredExpiryAndClockSkewEnd() {
        var created = NOW.minus(Duration.ofHours(2));
        var expires = NOW.minus(Duration.ofHours(1));

        assertThat(verifier(scheme(claim(created, expires))).verify(REQUEST)).isNotNull();
    }

    @Test
    void rejectsAfterACoveredExpiryAndClockSkewEnd() {
        var created = NOW.minus(Duration.ofHours(2));
        var expires = NOW.minus(Duration.ofHours(1)).minusSeconds(1);

        assertFails(verifier(scheme(claim(created, expires))), Failure.REJECTED);
    }

    @Test
    void capsTheLifetimeASignatureCanClaim() {
        var expires = NOW.plus(Duration.ofDays(365));
        var justInside = NOW.minus(Duration.ofHours(13));
        var justOutside = justInside.minusSeconds(1);

        assertThat(verifier(scheme(claim(justInside, expires))).verify(REQUEST)).isNotNull();
        assertFails(verifier(scheme(claim(justOutside, expires))), Failure.REJECTED);
    }

    @Test
    void rejectsAKeyIdItWontLookUpWithoutAskingForTheKey() {
        var claim = new SignatureClaim(
                "acct:bob@remote.example", SignatureAlgorithm.RSA_SHA256, BASE, sign(BASE), NOW, null);

        assertFails(verifier(scheme(claim)), Failure.REJECTED);
        verify(keys, never()).resolve(any());
    }

    @Test
    void passesOnAKeyFailure() {
        when(keys.resolve(any())).thenThrow(new SignatureVerificationException(Failure.KEY_UNAVAILABLE, "down"));

        assertFails(verifier(scheme(claim(NOW, null))), Failure.KEY_UNAVAILABLE);
    }

    @Test
    void refreshesTheKeyWhenTheSignatureDoesNotMatch() {
        var staleKey = new ResolvedKey(KEY_ID, OWNER, otherKey());
        when(keys.resolve(any())).thenReturn(staleKey);
        when(keys.refresh(any(), any())).thenReturn(Optional.of(key));

        assertThat(verifier(scheme(claim(NOW, null))).verify(REQUEST)).isEqualTo(new VerifiedSignature(KEY_ID, OWNER));
        verify(keys).refresh(any(), eq(staleKey));
    }

    @Test
    void doesNotRefreshWhenTheSignatureMatches() {
        verifier(scheme(claim(NOW, null))).verify(REQUEST);

        verify(keys, never()).refresh(any(), any());
    }

    @Test
    void rejectsIfThereIsNoDifferentKeyToTry() {
        when(keys.resolve(any())).thenReturn(new ResolvedKey(KEY_ID, OWNER, otherKey()));
        when(keys.refresh(any(), any())).thenReturn(Optional.empty());

        assertFails(verifier(scheme(claim(NOW, null))), Failure.REJECTED);
    }

    @Test
    void rejectsIfTheRefreshedKeyDoesNotMatchEither() {
        when(keys.resolve(any())).thenReturn(new ResolvedKey(KEY_ID, OWNER, otherKey()));
        when(keys.refresh(any(), any())).thenReturn(Optional.of(new ResolvedKey(KEY_ID, OWNER, otherKey())));

        assertFails(verifier(scheme(invalidClaim(NOW))), Failure.REJECTED);
    }

    @Test
    void passesOnAFailureFromARefresh() {
        when(keys.resolve(any())).thenReturn(new ResolvedKey(KEY_ID, OWNER, otherKey()));
        when(keys.refresh(any(), any())).thenThrow(new SignatureVerificationException(Failure.KEY_UNAVAILABLE, "down"));

        assertFails(verifier(scheme(claim(NOW, null))), Failure.KEY_UNAVAILABLE);
    }

    private SignatureVerifier verifier(SignatureScheme... schemes) {
        return new SignatureVerifier(List.of(schemes), keys, INSTANCE, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void assertFails(SignatureVerifier verifier, Failure failure) {
        assertThatThrownBy(() -> verifier.verify(REQUEST))
                .isInstanceOfSatisfying(SignatureVerificationException.class,
                        e -> assertThat(e.failure()).isEqualTo(failure));
    }

    private static FakeScheme scheme(SignatureClaim claim) {
        return new FakeScheme(true, claim);
    }

    private static SignatureClaim claim(Instant created, Instant expires) {
        return new SignatureClaim(KEY_ID, SignatureAlgorithm.RSA_SHA256, BASE, sign(BASE), created, expires);
    }

    private static SignatureClaim invalidClaim(Instant created) {
        return new SignatureClaim(KEY_ID, SignatureAlgorithm.RSA_SHA256, BASE, new byte[256], created, null);
    }

    private static byte[] sign(byte[] base) {
        return SignatureAlgorithm.RSA_SHA256.sign(SignatureFixtures.privateKey(), base);
    }

    private static PublicKey otherKey() {
        return PublicKeyPem.parse(SignatureFixtures.newPublicKeyPem(2048));
    }

    /**
     * A signature format that always gives the same claim.
     */
    private record FakeScheme(boolean applies, SignatureClaim claim) implements SignatureScheme {

        @Override
        public boolean appliesTo(HttpRequestData request) {
            return applies;
        }

        @Override
        public SignatureClaim parse(HttpRequestData request) {
            return claim;
        }
    }
}
