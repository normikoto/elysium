package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException.Failure;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProofKeysTests {

    private static final InstanceProperties INSTANCE = new InstanceProperties(URI.create("https://example.test"));
    private static final String KEY_ID = "https://remote.example/users/bob#ed25519-key";
    private static final URI OWNER = URI.create("https://remote.example/users/bob");

    private final PublicKeyResolver keys = mock();
    private final DomainBlocks blocks = mock();
    private final ActorService actors = mock();
    private final ProofKeys proofKeys = new ProofKeys(keys, INSTANCE, blocks, actors);
    private final PublicKey key = ed25519();
    private final PublicKey otherKey = ed25519();

    @Test
    void returnsTheOwnerOfAKeyForProofsThatMatches() {
        when(keys.resolve(any(), any())).thenReturn(proofKey(key));

        assertThat(proofKeys.verify(KEY_ID, key::equals, SignatureFixtures.UNLIMITED)).contains(OWNER);
    }

    @Test
    void refusesAKeyThatIsOnlyForHttpSignatures() {
        var forHttp = new ResolvedKey(KEY_ID, OWNER, key, ResolvedKey.Use.HTTP_SIGNATURES);
        when(keys.resolve(any(), any())).thenReturn(forHttp);
        when(keys.refresh(any(), any(), any())).thenReturn(Optional.of(forHttp));

        assertThat(proofKeys.verify(KEY_ID, key::equals, SignatureFixtures.UNLIMITED)).isEmpty();
    }

    @Test
    void fetchesTheKeyAgainWhenTheCachedOneDoesNotMatch() {
        when(keys.resolve(any(), any())).thenReturn(proofKey(otherKey));
        when(keys.refresh(any(), any(), any())).thenReturn(Optional.of(proofKey(key)));

        assertThat(proofKeys.verify(KEY_ID, key::equals, SignatureFixtures.UNLIMITED)).contains(OWNER);
    }

    @Test
    void refusesASignatureThatMatchesNeitherKey() {
        when(keys.resolve(any(), any())).thenReturn(proofKey(otherKey));
        when(keys.refresh(any(), any(), any())).thenReturn(Optional.empty());

        assertThat(proofKeys.verify(KEY_ID, key::equals, SignatureFixtures.UNLIMITED)).isEmpty();
    }

    @Test
    void doesNotLookUpAKeyOnABlockedServer() {
        when(blocks.isBlocked("remote.example")).thenReturn(true);

        assertThat(proofKeys.verify(KEY_ID, key::equals, SignatureFixtures.UNLIMITED)).isEmpty();
        verify(keys, never()).resolve(any(), any());
    }

    @Test
    void doesNotLookUpAKeyOnThisServer() {
        assertThat(proofKeys.verify("https://example.test/users/alice#ed25519-key", key::equals, SignatureFixtures.UNLIMITED))
                .isEmpty();
        verify(keys, never()).resolve(any(), any());
    }

    @Test
    void refusesTheKeyOfASuspendedActor() {
        when(keys.resolve(any(), any())).thenReturn(proofKey(key));
        when(actors.isInactive(OWNER)).thenReturn(true);

        assertThat(proofKeys.verify(KEY_ID, key::equals, SignatureFixtures.UNLIMITED)).isEmpty();
    }

    @Test
    void refusesWhenTheKeyCannotBeFetched() {
        when(keys.resolve(any(), any()))
                .thenThrow(new SignatureVerificationException(Failure.FETCH_BUDGET_SPENT, "spent"));

        assertThat(proofKeys.verify(KEY_ID, key::equals, SignatureFixtures.UNLIMITED)).isEmpty();
    }

    @Test
    void refusesAKeyIdItWontLookUp() {
        assertThat(proofKeys.verify("did:key:z6Mk#z6Mk", key::equals, SignatureFixtures.UNLIMITED)).isEmpty();
        verify(keys, never()).resolve(any(), any());
    }

    private static ResolvedKey proofKey(PublicKey key) {
        return new ResolvedKey(KEY_ID, OWNER, key, ResolvedKey.Use.INTEGRITY_PROOFS);
    }

    private static PublicKey ed25519() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
