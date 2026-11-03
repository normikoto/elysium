package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException.Failure;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicKeyResolverTests {

    private static final InstanceProperties INSTANCE = new InstanceProperties(URI.create("https://example.test"));
    private static final String KEY_ID = "https://remote.example/users/bob#main-key";
    private static final URI OWNER = URI.create("https://remote.example/users/bob");
    private static final ResolvedKey KEY = new ResolvedKey(KEY_ID, OWNER, SignatureFixtures.publicKey());
    private static final ResolvedKey NEW_KEY =
            new ResolvedKey(KEY_ID, OWNER, PublicKeyPem.parse(SignatureFixtures.newPublicKeyPem(2048)));

    private final AtomicLong nanos = new AtomicLong();
    private final ActorService actors = mock();
    private final KeyDocuments documents = mock();
    private final PublicKeyResolver resolver = new PublicKeyResolver(actors, documents, nanos::get, Runnable::run);

    @Test
    void remembersAFetchedKey() throws Exception {
        when(documents.fetch(any())).thenReturn(KEY);

        assertThat(resolver.resolve(keyId(KEY_ID))).isEqualTo(KEY);
        assertThat(resolver.resolve(keyId(KEY_ID))).isEqualTo(KEY);

        verify(documents, times(1)).fetch(any());
    }

    @Test
    void fetchesAKeyAgainAfterADay() throws Exception {
        when(documents.fetch(any())).thenReturn(KEY);

        resolver.resolve(keyId(KEY_ID));
        advance(Duration.ofHours(24).minusSeconds(1));
        resolver.resolve(keyId(KEY_ID));
        verify(documents, times(1)).fetch(any());

        advance(Duration.ofSeconds(1));
        resolver.resolve(keyId(KEY_ID));
        verify(documents, times(2)).fetch(any());
    }

    @Test
    void makesOneFetchForRequestsThatArriveTogether() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        when(documents.fetch(any())).thenAnswer(invocation -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return KEY;
        });
        var concurrent = new PublicKeyResolver(
                actors, documents, nanos::get, Executors.newVirtualThreadPerTaskExecutor());

        var requests = new ArrayList<CompletableFuture<ResolvedKey>>();
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var i = 0; i < 10; i++) {
                requests.add(CompletableFuture.supplyAsync(() -> concurrent.resolve(keyId(KEY_ID)), callers));
            }
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
        }

        assertThat(requests).allSatisfy(request -> assertThat(request.get()).isEqualTo(KEY));
        verify(documents, times(1)).fetch(any());
    }

    @Test
    void remembersAPermanentFailureForTenMinutes() throws Exception {
        when(documents.fetch(any())).thenThrow(KeyFetchException.permanent("gone", null));

        assertFails(KEY_ID, Failure.REJECTED);
        advance(Duration.ofMinutes(10).minusSeconds(1));
        assertFails(KEY_ID, Failure.REJECTED);
        verify(documents, times(1)).fetch(any());

        advance(Duration.ofSeconds(1));
        assertFails(KEY_ID, Failure.REJECTED);
        verify(documents, times(2)).fetch(any());
    }

    @Test
    void remembersATemporaryFailureForAMinute() throws Exception {
        when(documents.fetch(any())).thenThrow(KeyFetchException.temporary("down", null));

        assertFails(KEY_ID, Failure.KEY_UNAVAILABLE);
        advance(Duration.ofMinutes(1).minusSeconds(1));
        assertFails(KEY_ID, Failure.KEY_UNAVAILABLE);
        verify(documents, times(1)).fetch(any());

        advance(Duration.ofSeconds(1));
        assertFails(KEY_ID, Failure.KEY_UNAVAILABLE);
        verify(documents, times(2)).fetch(any());
    }

    @Test
    void treatsAnUnexpectedFailureAsTemporary() throws Exception {
        when(documents.fetch(any())).thenThrow(new IllegalStateException("bug"));

        assertFails(KEY_ID, Failure.KEY_UNAVAILABLE);
    }

    @Test
    void limitsFetchesPerHost() throws Exception {
        when(documents.fetch(any())).thenThrow(KeyFetchException.permanent("gone", null));

        for (var i = 0; i < 60; i++) {
            assertFails("https://remote.example/users/u" + i + "#main-key", Failure.REJECTED);
        }
        assertFails("https://remote.example/users/over#main-key", Failure.KEY_UNAVAILABLE);
        verify(documents, times(60)).fetch(any());

        // Other hosts have their own limit, and this one's starts again in the next window.
        assertFails("https://other.example/users/u#main-key", Failure.REJECTED);
        advance(Duration.ofMinutes(1));
        assertFails("https://remote.example/users/next#main-key", Failure.REJECTED);
    }

    /**
     * Otherwise, using different ports would get around the limit.
     */
    @Test
    void countsEveryPortOfAHostTowardItsLimit() throws Exception {
        when(documents.fetch(any())).thenThrow(KeyFetchException.permanent("gone", null));

        for (var i = 0; i < 60; i++) {
            assertFails("https://remote.example:" + (1000 + i) + "/key", Failure.REJECTED);
        }
        assertFails("https://REMOTE.example:2000/key", Failure.KEY_UNAVAILABLE);

        verify(documents, times(60)).fetch(any());
    }

    @Test
    void doesNotRememberBeingOverTheLimit() throws Exception {
        when(documents.fetch(any())).thenReturn(KEY);
        for (var i = 0; i < 60; i++) {
            resolver.resolve(keyId("https://remote.example/users/u" + i + "#main-key"));
        }
        assertFails(KEY_ID, Failure.KEY_UNAVAILABLE);

        advance(Duration.ofMinutes(1));

        assertThat(resolver.resolve(keyId(KEY_ID))).isEqualTo(KEY);
    }

    @Test
    void limitsFetchesRunningAtOnce() throws Exception {
        var release = new CountDownLatch(1);
        var running = new CountDownLatch(32);
        when(documents.fetch(any())).thenAnswer(invocation -> {
            running.countDown();
            release.await(10, TimeUnit.SECONDS);
            return KEY;
        });
        var concurrent = new PublicKeyResolver(
                actors, documents, nanos::get, Executors.newVirtualThreadPerTaskExecutor());

        var requests = new ArrayList<CompletableFuture<ResolvedKey>>();
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var i = 0; i < 32; i++) {
                var id = keyId("https://host" + i + ".example/k");
                requests.add(CompletableFuture.supplyAsync(() -> concurrent.resolve(id), callers));
            }
            assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> concurrent.resolve(keyId("https://over.example/k")))
                    .isInstanceOfSatisfying(SignatureVerificationException.class,
                            e -> assertThat(e.failure()).isEqualTo(Failure.KEY_UNAVAILABLE));
            release.countDown();
        }

        // Finished fetches give their places back.
        assertThat(concurrent.resolve(keyId("https://over.example/k"))).isEqualTo(KEY);
    }

    @Test
    void refreshesAKeyThatIsOldEnough() throws Exception {
        when(documents.fetch(any())).thenReturn(KEY, NEW_KEY);
        var stale = resolver.resolve(keyId(KEY_ID));

        advance(Duration.ofMinutes(5));

        assertThat(resolver.refresh(keyId(KEY_ID), stale)).contains(NEW_KEY);
        assertThat(resolver.resolve(keyId(KEY_ID))).isEqualTo(NEW_KEY);
    }

    @Test
    void doesNotRefreshAKeyThatWasJustFetched() throws Exception {
        when(documents.fetch(any())).thenReturn(KEY);
        var stale = resolver.resolve(keyId(KEY_ID));

        advance(Duration.ofMinutes(5).minusSeconds(1));

        assertThat(resolver.refresh(keyId(KEY_ID), stale)).isEmpty();
        verify(documents, times(1)).fetch(any());
    }

    @Test
    void reportsNoNewKeyIfTheKeyHasNotChanged() throws Exception {
        when(documents.fetch(any())).thenReturn(KEY);
        var stale = resolver.resolve(keyId(KEY_ID));
        advance(Duration.ofMinutes(5));

        assertThat(resolver.refresh(keyId(KEY_ID), stale)).isEmpty();
        verify(documents, times(2)).fetch(any());

        // That counts as a fetch, so bad signatures can't keep causing them.
        assertThat(resolver.refresh(keyId(KEY_ID), stale)).isEmpty();
        verify(documents, times(2)).fetch(any());
    }

    @Test
    void usesAKeyAnotherRequestAlreadyRefreshed() throws Exception {
        when(documents.fetch(any())).thenReturn(KEY, NEW_KEY);
        var stale = resolver.resolve(keyId(KEY_ID));
        advance(Duration.ofMinutes(5));
        resolver.refresh(keyId(KEY_ID), stale);

        assertThat(resolver.refresh(keyId(KEY_ID), stale)).contains(NEW_KEY);
        verify(documents, times(2)).fetch(any());
    }

    @Test
    void doesNotTryAgainSoonAfterARefreshFailsForNow() throws Exception {
        when(documents.fetch(any()))
                .thenReturn(KEY)
                .thenThrow(KeyFetchException.temporary("down", null))
                .thenReturn(NEW_KEY);
        var stale = resolver.resolve(keyId(KEY_ID));
        advance(Duration.ofMinutes(5));

        assertRefreshFails(stale, Failure.KEY_UNAVAILABLE);
        advance(Duration.ofMinutes(5).minusSeconds(1));
        assertRefreshFails(stale, Failure.KEY_UNAVAILABLE);
        verify(documents, times(2)).fetch(any());

        advance(Duration.ofSeconds(1));
        assertThat(resolver.refresh(keyId(KEY_ID), stale)).contains(NEW_KEY);
        verify(documents, times(3)).fetch(any());
    }

    @Test
    void doesNotLetFailedRefreshesKeepAnOldKeyAlive() throws Exception {
        when(documents.fetch(any()))
                .thenReturn(KEY)
                .thenThrow(KeyFetchException.temporary("down", null));
        var stale = resolver.resolve(keyId(KEY_ID));

        advance(Duration.ofHours(23));
        assertRefreshFails(stale, Failure.KEY_UNAVAILABLE);
        advance(Duration.ofHours(1));

        assertFails(KEY_ID, Failure.KEY_UNAVAILABLE);
    }

    @Test
    void keepsTheKeyIfARefreshGetsAnAnswerThatIsNotGone() throws Exception {
        when(documents.fetch(any()))
                .thenReturn(KEY)
                .thenThrow(KeyFetchException.permanent("a firewall said 403", null))
                .thenReturn(NEW_KEY);
        var stale = resolver.resolve(keyId(KEY_ID));
        advance(Duration.ofMinutes(5));

        assertThat(resolver.refresh(keyId(KEY_ID), stale)).isEmpty();
        assertThat(resolver.resolve(keyId(KEY_ID))).isEqualTo(KEY);

        // Also not tried again until the time is up.
        assertThat(resolver.refresh(keyId(KEY_ID), stale)).isEmpty();
        verify(documents, times(2)).fetch(any());
        advance(Duration.ofMinutes(5));
        assertThat(resolver.refresh(keyId(KEY_ID), stale)).contains(NEW_KEY);
    }

    @Test
    void doesNotRememberARefreshThatWasOverTheLimit() throws Exception {
        when(documents.fetch(any())).thenReturn(KEY);
        var stale = resolver.resolve(keyId(KEY_ID));
        advance(Duration.ofMinutes(5));
        for (var i = 0; i < 60; i++) {
            resolver.resolve(keyId("https://remote.example/users/u" + i + "#main-key"));
        }
        assertRefreshFails(stale, Failure.KEY_UNAVAILABLE);
        verify(documents, times(61)).fetch(any());

        advance(Duration.ofMinutes(1));

        when(documents.fetch(any())).thenReturn(NEW_KEY);
        assertThat(resolver.refresh(keyId(KEY_ID), stale)).contains(NEW_KEY);
    }

    @Test
    void keepsTheOldKeyIfARefreshFailsForNow() throws Exception {
        when(documents.fetch(any()))
                .thenReturn(KEY)
                .thenThrow(KeyFetchException.temporary("down", null));
        var stale = resolver.resolve(keyId(KEY_ID));
        advance(Duration.ofMinutes(5));

        assertThatThrownBy(() -> resolver.refresh(keyId(KEY_ID), stale))
                .isInstanceOfSatisfying(SignatureVerificationException.class,
                        e -> assertThat(e.failure()).isEqualTo(Failure.KEY_UNAVAILABLE));

        assertThat(resolver.resolve(keyId(KEY_ID))).isEqualTo(KEY);
    }

    @Test
    void forgetsTheKeyIfItIsGoneOnRefresh() throws Exception {
        when(documents.fetch(any()))
                .thenReturn(KEY)
                .thenThrow(KeyFetchException.gone("gone"));
        var stale = resolver.resolve(keyId(KEY_ID));
        advance(Duration.ofMinutes(5));

        assertRefreshFails(stale, Failure.REJECTED);

        assertFails(KEY_ID, Failure.REJECTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.test/users/alice#main-key",
            "https://EXAMPLE.test:443/users/alice#main-key",
            "http://example.test/users/alice#main-key"
    })
    void readsLocalKeysFromTheDatabaseAndNeverFetchesThem(String value) throws Exception {
        var alice = localActor();
        when(actors.findLocalByUri(alice.uri())).thenReturn(Optional.of(alice));

        var key = resolver.resolve(keyId(value));

        assertThat(key.owner()).isEqualTo(alice.uri());
        assertThat(key.key()).isEqualTo(SignatureFixtures.publicKey());
        verify(documents, never()).fetch(any());
    }

    @Test
    void neverFetchesAKeyOnThisServerThatDoesNotExist() throws Exception {
        when(actors.findLocalByUri(any())).thenReturn(Optional.empty());

        assertFails("https://example.test/users/nobody#main-key", Failure.REJECTED);

        verify(documents, never()).fetch(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.test/users/alice#other", "https://example.test/users/alice"})
    void requiresTheExactIdOfALocalKey(String value) {
        var alice = localActor();
        when(actors.findLocalByUri(alice.uri())).thenReturn(Optional.of(alice));

        assertFails(value, Failure.REJECTED);
    }

    @Test
    void neverRefreshesLocalKeys() throws Exception {
        var alice = localActor();
        when(actors.findLocalByUri(alice.uri())).thenReturn(Optional.of(alice));
        var id = keyId(alice.publicKeyId());

        assertThat(resolver.refresh(id, resolver.resolve(id))).isEmpty();

        verify(documents, never()).fetch(any());
    }

    private static Actor localActor() {
        var uri = URI.create("https://example.test/users/alice");
        return new Actor(
                UUID.randomUUID(), uri, "alice", "example.test", URI.create(uri + "/inbox"), URI.create(uri + "/outbox"),
                null, SignatureFixtures.PUBLIC_KEY_PEM, Instant.EPOCH);
    }

    private void assertRefreshFails(ResolvedKey stale, Failure failure) {
        assertThatThrownBy(() -> resolver.refresh(keyId(KEY_ID), stale))
                .isInstanceOfSatisfying(SignatureVerificationException.class,
                        e -> assertThat(e.failure()).isEqualTo(failure));
    }

    private void assertFails(String id, Failure failure) {
        assertThatThrownBy(() -> resolver.resolve(keyId(id)))
                .isInstanceOfSatisfying(SignatureVerificationException.class,
                        e -> assertThat(e.failure()).isEqualTo(failure));
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    private static KeyId keyId(String value) {
        return KeyId.parse(value, INSTANCE);
    }
}
