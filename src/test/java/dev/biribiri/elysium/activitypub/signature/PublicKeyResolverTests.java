package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.http.HostRequests;
import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException.Failure;
import dev.biribiri.elysium.activitypub.vocab.Multikey;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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

    private static final KeyFetchBudget UNLIMITED = SignatureFixtures.UNLIMITED;

    private static final InstanceProperties INSTANCE = new InstanceProperties(URI.create("https://example.test"));
    private static final String KEY_ID = "https://remote.example/users/bob#main-key";
    private static final URI OWNER = URI.create("https://remote.example/users/bob");
    private static final ResolvedKey KEY = new ResolvedKey(KEY_ID, OWNER, SignatureFixtures.publicKey(), ResolvedKey.Use.HTTP_SIGNATURES);
    private static final ResolvedKey NEW_KEY =
            new ResolvedKey(KEY_ID, OWNER, PublicKeyPem.parse(SignatureFixtures.newPublicKeyPem(2048)), ResolvedKey.Use.HTTP_SIGNATURES);

    private final AtomicLong nanos = new AtomicLong();
    private final ActorService actors = mock();
    private final KeyDocuments documents = mock();
    private final JsonMapper json = JsonMapper.builder().build();
    private final PublicKeyResolver resolver = new PublicKeyResolver(actors, documents, nanos::get, Runnable::run, new HostRequests());

    @Test
    void remembersAFetchedKeyForADay() throws Exception {
        when(documents.fetch(any())).thenReturn(KEY);

        resolver.resolve(keyId(KEY_ID), UNLIMITED);
        advance(Duration.ofHours(24).minusSeconds(1));
        assertThat(resolver.resolve(keyId(KEY_ID), UNLIMITED)).isEqualTo(KEY);
        verify(documents, times(1)).fetch(any());

        advance(Duration.ofSeconds(1));
        resolver.resolve(keyId(KEY_ID), UNLIMITED);
        verify(documents, times(2)).fetch(any());
    }

    @ParameterizedTest
    @CsvSource({"true, REJECTED, 10", "false, KEY_UNAVAILABLE, 1"})
    void remembersAPermanentFailureForTenMinutesAndATemporaryOneForAMinute(
            boolean permanent, Failure failure, long minutes) throws Exception {
        when(documents.fetch(any())).thenThrow(permanent
                ? DocumentFetchException.permanent("gone", null)
                : DocumentFetchException.temporary("down", null));

        assertFails(KEY_ID, failure);
        advance(Duration.ofMinutes(minutes).minusSeconds(1));
        assertFails(KEY_ID, failure);
        verify(documents, times(1)).fetch(any());

        advance(Duration.ofSeconds(1));
        assertFails(KEY_ID, failure);
        verify(documents, times(2)).fetch(any());
    }

    @Test
    void treatsAnUnexpectedFailureAsTemporary() throws Exception {
        when(documents.fetch(any())).thenThrow(new IllegalStateException("bug"));

        assertFails(KEY_ID, Failure.KEY_UNAVAILABLE);
    }

    @Test
    void limitsFetchesPerHost() throws Exception {
        when(documents.fetch(any())).thenThrow(DocumentFetchException.permanent("gone", null));

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
        when(documents.fetch(any())).thenThrow(DocumentFetchException.permanent("gone", null));

        for (var i = 0; i < 60; i++) {
            assertFails("https://remote.example:" + (1000 + i) + "/key", Failure.REJECTED);
        }
        assertFails("https://REMOTE.example:2000/key", Failure.KEY_UNAVAILABLE);

        verify(documents, times(60)).fetch(any());
    }

    @Test
    void neitherChargesTheSenderNorRemembersAFetchOverTheHostsLimit() throws Exception {
        when(documents.fetch(any())).thenReturn(KEY);
        for (var i = 0; i < 60; i++) {
            resolver.resolve(keyId("https://remote.example/users/u" + i + "#main-key"), UNLIMITED);
        }
        var budget = new CountingBudget(1);

        assertThatThrownBy(() -> resolver.resolve(keyId(KEY_ID), budget))
                .isInstanceOfSatisfying(SignatureVerificationException.class,
                        e -> assertThat(e.failure()).isEqualTo(Failure.KEY_UNAVAILABLE));
        assertThat(budget.cancelled).as("cancelled reservations").isEqualTo(1);
        assertThat(budget.finished).as("finished reservations").isZero();

        advance(Duration.ofMinutes(1));

        assertThat(resolver.resolve(keyId(KEY_ID), UNLIMITED)).as("in the next window").isEqualTo(KEY);
    }

    /**
     * Requests turned away while every place is taken don't spend the host's budget, or a few slow hosts could use up
     * another's.
     */
    @Test
    void limitsFetchesRunningAtOnceWithoutSpendingTheHostsBudget() throws Exception {
        var release = new CountDownLatch(1);
        var running = new CountDownLatch(32);
        when(documents.fetch(any())).thenAnswer(invocation -> {
            running.countDown();
            release.await(10, TimeUnit.SECONDS);
            return KEY;
        });
        var concurrent = new PublicKeyResolver(
                actors, documents, nanos::get, Executors.newVirtualThreadPerTaskExecutor(), new HostRequests());

        var requests = new ArrayList<CompletableFuture<ResolvedKey>>();
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var i = 0; i < 32; i++) {
                var id = keyId("https://host" + i + ".example/k");
                requests.add(CompletableFuture.supplyAsync(() -> concurrent.resolve(id, UNLIMITED), callers));
            }
            assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();

            for (var i = 0; i < 61; i++) {
                assertThatThrownBy(() -> concurrent.resolve(keyId("https://over.example/k"), UNLIMITED))
                        .isInstanceOfSatisfying(SignatureVerificationException.class,
                                e -> assertThat(e.failure()).isEqualTo(Failure.KEY_UNAVAILABLE));
            }
            release.countDown();
        }

        // Finished fetches give their places back.
        assertThat(concurrent.resolve(keyId("https://over.example/k"), UNLIMITED)).isEqualTo(KEY);
    }

    /**
     * Otherwise, a few slow hosts could take every fetch, and senders on other hosts would be turned away. After a
     * restart, a busy server can have more keys to fetch than its host can run at once. That's this server's limit, so
     * it shouldn't count against the sender.
     */
    @Test
    void limitsFetchesRunningAtOnceForAHostWithoutChargingTheSender() throws Exception {
        var release = new CountDownLatch(1);
        var running = new CountDownLatch(4);
        when(documents.fetch(any())).thenAnswer(invocation -> {
            KeyId id = invocation.getArgument(0);
            if (id.host().equals("slow.example")) {
                running.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            return KEY;
        });
        var concurrent = new PublicKeyResolver(
                actors, documents, nanos::get, Executors.newVirtualThreadPerTaskExecutor(), new HostRequests());
        var budget = new CountingBudget(1);

        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var i = 0; i < 4; i++) {
                var id = keyId("https://slow.example/k" + i);
                CompletableFuture.supplyAsync(() -> concurrent.resolve(id, UNLIMITED), callers);
            }
            assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> concurrent.resolve(keyId("https://slow.example/over"), budget))
                    .isInstanceOfSatisfying(SignatureVerificationException.class,
                            e -> assertThat(e.failure()).isEqualTo(Failure.KEY_UNAVAILABLE));
            assertThat(budget.reserved).isZero();
            assertThat(concurrent.resolve(keyId("https://other.example/k"), UNLIMITED)).isEqualTo(KEY);
            release.countDown();
        }

        assertThat(concurrent.resolve(keyId("https://slow.example/over"), UNLIMITED)).isEqualTo(KEY);
    }

    /**
     * A server with many actors can use up its budget after a restart, and its keys that are cached have to keep working.
     */
    @Test
    void chargesTheSenderOnlyForAFetchItStartsAndServesTheCachedKeyWithNoBudgetLeft() throws Exception {
        when(documents.fetch(any())).thenReturn(KEY);
        var budget = new CountingBudget(1);

        assertThat(resolver.resolve(keyId(KEY_ID), budget)).isEqualTo(KEY);
        assertThat(budget.reserved).as("reserved by the fetch").isEqualTo(1);
        assertThat(budget.finished).as("finished by the fetch").isEqualTo(1);

        assertThat(resolver.resolve(keyId(KEY_ID), budget)).as("cached, with the budget spent").isEqualTo(KEY);
        assertThat(budget.reserved).as("reserved after the cached lookup").isEqualTo(1);
    }

    @Test
    void makesOneFetchAndChargesOnlyTheFirstOfRequestsThatArriveTogether() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        when(documents.fetch(any())).thenAnswer(invocation -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return KEY;
        });
        var concurrent = new PublicKeyResolver(
                actors, documents, nanos::get, Executors.newVirtualThreadPerTaskExecutor(), new HostRequests());
        var first = new CountingBudget(1);
        var second = new CountingBudget(0);

        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var fetching = CompletableFuture.supplyAsync(() -> concurrent.resolve(keyId(KEY_ID), first), callers);
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            var waiting = CompletableFuture.supplyAsync(() -> concurrent.resolve(keyId(KEY_ID), second), callers);
            release.countDown();

            assertThat(fetching.get(10, TimeUnit.SECONDS)).isEqualTo(KEY);
            assertThat(waiting.get(10, TimeUnit.SECONDS)).isEqualTo(KEY);
        }

        assertThat(first.reserved).isEqualTo(1);
        assertThat(second.reserved).isZero();
        verify(documents, times(1)).fetch(any());
    }

    @Test
    void refusesAFetchOverTheSendersBudgetWithoutSpendingTheHosts() throws Exception {
        when(documents.fetch(any())).thenReturn(KEY);
        var spent = new CountingBudget(0);

        for (var i = 0; i < 61; i++) {
            var id = "https://remote.example/users/u" + i + "#main-key";
            assertThatThrownBy(() -> resolver.resolve(keyId(id), spent))
                    .isInstanceOfSatisfying(SignatureVerificationException.class,
                            e -> assertThat(e.failure()).isEqualTo(Failure.FETCH_BUDGET_SPENT));
        }
        verify(documents, never()).fetch(any());

        // It isn't remembered either, and the host still has its whole budget.
        assertThat(resolver.resolve(keyId("https://remote.example/users/u0#main-key"), UNLIMITED)).isEqualTo(KEY);
    }

    @Test
    void refreshesAKeyOnceItIsOldEnoughAndChargesTheSender() throws Exception {
        when(documents.fetch(any())).thenReturn(KEY, NEW_KEY);
        var stale = resolver.resolve(keyId(KEY_ID), UNLIMITED);

        advance(Duration.ofMinutes(5).minusSeconds(1));
        assertThat(resolver.refresh(keyId(KEY_ID), stale, UNLIMITED)).isEmpty();
        verify(documents, times(1)).fetch(any());

        advance(Duration.ofSeconds(1));
        var budget = new CountingBudget(1);
        assertThat(resolver.refresh(keyId(KEY_ID), stale, budget)).contains(NEW_KEY);
        assertThat(resolver.resolve(keyId(KEY_ID), UNLIMITED)).isEqualTo(NEW_KEY);
        assertThat(budget.reserved).isEqualTo(1);
    }

    @Test
    void reportsNoNewKeyIfTheKeyHasNotChanged() throws Exception {
        when(documents.fetch(any())).thenReturn(KEY);
        var stale = resolver.resolve(keyId(KEY_ID), UNLIMITED);
        advance(Duration.ofMinutes(5));

        assertThat(resolver.refresh(keyId(KEY_ID), stale, UNLIMITED)).isEmpty();
        verify(documents, times(2)).fetch(any());

        // That counts as a fetch, so bad signatures can't keep causing them.
        assertThat(resolver.refresh(keyId(KEY_ID), stale, UNLIMITED)).isEmpty();
        verify(documents, times(2)).fetch(any());
    }

    @Test
    void usesAKeyAnotherRequestAlreadyRefreshed() throws Exception {
        when(documents.fetch(any())).thenReturn(KEY, NEW_KEY);
        var stale = resolver.resolve(keyId(KEY_ID), UNLIMITED);
        advance(Duration.ofMinutes(5));
        resolver.refresh(keyId(KEY_ID), stale, UNLIMITED);

        assertThat(resolver.refresh(keyId(KEY_ID), stale, UNLIMITED)).contains(NEW_KEY);
        verify(documents, times(2)).fetch(any());
    }

    @Test
    void doesNotTryAgainSoonAfterARefreshFailsForNow() throws Exception {
        when(documents.fetch(any()))
                .thenReturn(KEY)
                .thenThrow(DocumentFetchException.temporary("down", null))
                .thenReturn(NEW_KEY);
        var stale = resolver.resolve(keyId(KEY_ID), UNLIMITED);
        advance(Duration.ofMinutes(5));

        assertRefreshFails(stale, Failure.KEY_UNAVAILABLE);
        assertThat(resolver.resolve(keyId(KEY_ID), UNLIMITED)).isEqualTo(KEY);
        advance(Duration.ofMinutes(5).minusSeconds(1));
        assertRefreshFails(stale, Failure.KEY_UNAVAILABLE);
        verify(documents, times(2)).fetch(any());

        advance(Duration.ofSeconds(1));
        assertThat(resolver.refresh(keyId(KEY_ID), stale, UNLIMITED)).contains(NEW_KEY);
        verify(documents, times(3)).fetch(any());
    }

    @Test
    void doesNotLetFailedRefreshesKeepAnOldKeyAlive() throws Exception {
        when(documents.fetch(any()))
                .thenReturn(KEY)
                .thenThrow(DocumentFetchException.temporary("down", null));
        var stale = resolver.resolve(keyId(KEY_ID), UNLIMITED);

        advance(Duration.ofHours(23));
        assertRefreshFails(stale, Failure.KEY_UNAVAILABLE);
        advance(Duration.ofHours(1));

        assertFails(KEY_ID, Failure.KEY_UNAVAILABLE);
    }

    @Test
    void keepsTheKeyIfARefreshGetsAnAnswerThatIsNotGone() throws Exception {
        when(documents.fetch(any()))
                .thenReturn(KEY)
                .thenThrow(DocumentFetchException.permanent("a firewall said 403", null))
                .thenReturn(NEW_KEY);
        var stale = resolver.resolve(keyId(KEY_ID), UNLIMITED);
        advance(Duration.ofMinutes(5));

        assertThat(resolver.refresh(keyId(KEY_ID), stale, UNLIMITED)).isEmpty();
        assertThat(resolver.resolve(keyId(KEY_ID), UNLIMITED)).isEqualTo(KEY);

        // Also not tried again until the time is up.
        assertThat(resolver.refresh(keyId(KEY_ID), stale, UNLIMITED)).isEmpty();
        verify(documents, times(2)).fetch(any());
        advance(Duration.ofMinutes(5));
        assertThat(resolver.refresh(keyId(KEY_ID), stale, UNLIMITED)).contains(NEW_KEY);
    }

    @Test
    void doesNotRememberARefreshThatWasOverTheLimit() throws Exception {
        when(documents.fetch(any())).thenReturn(KEY);
        var stale = resolver.resolve(keyId(KEY_ID), UNLIMITED);
        advance(Duration.ofMinutes(5));
        for (var i = 0; i < 60; i++) {
            resolver.resolve(keyId("https://remote.example/users/u" + i + "#main-key"), UNLIMITED);
        }
        assertRefreshFails(stale, Failure.KEY_UNAVAILABLE);
        verify(documents, times(61)).fetch(any());

        advance(Duration.ofMinutes(1));

        when(documents.fetch(any())).thenReturn(NEW_KEY);
        assertThat(resolver.refresh(keyId(KEY_ID), stale, UNLIMITED)).contains(NEW_KEY);
    }

    @Test
    void forgetsTheKeyIfItIsGoneOnRefresh() throws Exception {
        when(documents.fetch(any()))
                .thenReturn(KEY)
                .thenThrow(DocumentFetchException.gone("gone"));
        var stale = resolver.resolve(keyId(KEY_ID), UNLIMITED);
        advance(Duration.ofMinutes(5));

        assertRefreshFails(stale, Failure.KEY_GONE);

        assertFails(KEY_ID, Failure.KEY_GONE);
    }

    @Test
    void saysWhoseKeyIsGone() throws Exception {
        when(documents.fetch(any())).thenThrow(DocumentFetchException.gone("gone"));

        assertThatThrownBy(() -> resolver.resolve(keyId(KEY_ID), UNLIMITED))
                .isInstanceOfSatisfying(SignatureVerificationException.class, e -> {
                    assertThat(e.failure()).isEqualTo(Failure.KEY_GONE);
                    assertThat(e.failure().status()).isEqualTo(401);
                    assertThat(e.keyDocument()).contains(URI.create("https://remote.example/users/bob"));
                });
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

        var key = resolver.resolve(keyId(value), UNLIMITED);

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

        assertThat(resolver.refresh(id, resolver.resolve(id, UNLIMITED), UNLIMITED)).isEmpty();

        verify(documents, never()).fetch(any());
    }

    // Forgetting keys that an actor's document changed

    @Test
    void forgetsAKeyThatTheActorsDocumentChanged() throws Exception {
        when(documents.fetch(any())).thenReturn(KEY, NEW_KEY);
        resolver.resolve(keyId(KEY_ID), UNLIMITED);

        resolver.forgetChanged(OWNER, actorDocument(Map.of("id", KEY_ID, "publicKeyPem", SignatureFixtures.newPublicKeyPem(2048))));

        assertThat(resolver.resolve(keyId(KEY_ID), UNLIMITED)).isEqualTo(NEW_KEY);
        verify(documents, times(2)).fetch(any());
    }

    @Test
    void forgetsAProofKeyThatTheActorsAssertionMethodChanged() throws Exception {
        var proofKeyId = OWNER + "#ed25519-key";
        var first = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
        var cached = new ResolvedKey(proofKeyId, OWNER, first, ResolvedKey.Use.INTEGRITY_PROOFS);
        when(documents.fetch(any())).thenReturn(cached);
        resolver.resolve(keyId(proofKeyId), UNLIMITED);

        resolver.forgetChanged(OWNER, assertionMethodDocument(proofKeyId, first));
        resolver.resolve(keyId(proofKeyId), UNLIMITED);
        verify(documents, times(1)).fetch(any());

        var second = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
        resolver.forgetChanged(OWNER, assertionMethodDocument(proofKeyId, second));
        resolver.resolve(keyId(proofKeyId), UNLIMITED);
        verify(documents, times(2)).fetch(any());
    }

    @Test
    void forgetsAKeyThatTheActorsDocumentNoLongerHas() throws Exception {
        when(documents.fetch(any())).thenReturn(KEY);
        resolver.resolve(keyId(KEY_ID), UNLIMITED);

        resolver.forgetChanged(OWNER, actorDocument(Map.of("id", OWNER + "#other-key", "publicKeyPem", SignatureFixtures.PUBLIC_KEY_PEM)));

        resolver.resolve(keyId(KEY_ID), UNLIMITED);
        verify(documents, times(2)).fetch(any());
    }

    @Test
    void keepsAKeyThatTheActorsDocumentStillHas() throws Exception {
        when(documents.fetch(any())).thenReturn(KEY);
        resolver.resolve(keyId(KEY_ID), UNLIMITED);

        resolver.forgetChanged(OWNER, actorDocument(Map.of("id", KEY_ID, "publicKeyPem", SignatureFixtures.PUBLIC_KEY_PEM)));
        // Only an ID, as when the key is a separate document, says nothing about whether it changed.
        resolver.forgetChanged(OWNER, json.readTree("{\"publicKey\": \"" + KEY_ID + "\"}"));

        resolver.resolve(keyId(KEY_ID), UNLIMITED);
        verify(documents, times(1)).fetch(any());
    }

    @Test
    void keepsAKeyThatIsTheActorsOwnId() throws Exception {
        var ownId = new ResolvedKey(OWNER.toString(), OWNER, SignatureFixtures.publicKey(), ResolvedKey.Use.HTTP_SIGNATURES);
        when(documents.fetch(any())).thenReturn(ownId);
        resolver.resolve(keyId(OWNER.toString()), UNLIMITED);

        resolver.forgetChanged(OWNER, actorDocument(Map.of("publicKeyPem", SignatureFixtures.PUBLIC_KEY_PEM)));

        resolver.resolve(keyId(OWNER.toString()), UNLIMITED);
        verify(documents, times(1)).fetch(any());
    }

    @Test
    void forgetsAFailedLookupOfAKeyTheActorsDocumentNowLists() throws Exception {
        when(documents.fetch(any())).thenThrow(DocumentFetchException.permanent("not listed yet", null)).thenReturn(KEY);
        assertFails(KEY_ID, Failure.REJECTED);

        resolver.forgetChanged(OWNER, actorDocument(Map.of("id", KEY_ID, "publicKeyPem", SignatureFixtures.PUBLIC_KEY_PEM)));

        assertThat(resolver.resolve(keyId(KEY_ID), UNLIMITED)).isEqualTo(KEY);
    }

    @Test
    void keepsAFailedLookupOfAKeyOnAnotherServer() throws Exception {
        var elsewhere = "https://elsewhere.example/keys/1";
        when(documents.fetch(any())).thenThrow(DocumentFetchException.permanent("missing", null));
        assertFails(elsewhere, Failure.REJECTED);

        resolver.forgetChanged(OWNER, actorDocument(Map.of("id", elsewhere, "publicKeyPem", SignatureFixtures.PUBLIC_KEY_PEM)));

        assertFails(elsewhere, Failure.REJECTED);
        verify(documents, times(1)).fetch(any());
    }

    @Test
    void keepsAnotherActorsKeys() throws Exception {
        when(documents.fetch(any())).thenReturn(KEY);
        resolver.resolve(keyId(KEY_ID), UNLIMITED);

        resolver.forgetChanged(URI.create("https://remote.example/users/mallory"), actorDocument(Map.of()));

        resolver.resolve(keyId(KEY_ID), UNLIMITED);
        verify(documents, times(1)).fetch(any());
    }

    private JsonNode assertionMethodDocument(String keyId, PublicKey key) {
        var pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        return json.valueToTree(Map.of("assertionMethod", List.of(Multikey.ed25519(keyId, OWNER, pem))));
    }

    private JsonNode actorDocument(Map<String, Object> key) {
        return json.valueToTree(Map.of("publicKey", List.of(key)));
    }

    private static Actor localActor() {
        var uri = URI.create("https://example.test/users/alice");
        return new Actor(
                UUID.randomUUID(), uri, "alice", "example.test", URI.create(uri + "/inbox"), URI.create(uri + "/outbox"),
                null, SignatureFixtures.PUBLIC_KEY_PEM, Instant.EPOCH, Instant.EPOCH);
    }

    private void assertRefreshFails(ResolvedKey stale, Failure failure) {
        assertThatThrownBy(() -> resolver.refresh(keyId(KEY_ID), stale, UNLIMITED))
                .isInstanceOfSatisfying(SignatureVerificationException.class,
                        e -> assertThat(e.failure()).isEqualTo(failure));
    }

    private void assertFails(String id, Failure failure) {
        assertThatThrownBy(() -> resolver.resolve(keyId(id), UNLIMITED))
                .isInstanceOfSatisfying(SignatureVerificationException.class,
                        e -> assertThat(e.failure()).isEqualTo(failure));
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    private static KeyId keyId(String value) {
        return KeyId.parse(value, INSTANCE);
    }

    private static final class CountingBudget implements KeyFetchBudget {

        private final int limit;
        private int reserved;
        private int finished;
        private int cancelled;

        CountingBudget(int limit) {
            this.limit = limit;
        }

        @Override
        public synchronized Optional<Reservation> tryReserve() {
            if (reserved >= limit) {
                return Optional.empty();
            }
            reserved++;
            return Optional.of(new Reservation() {
                @Override
                public void finished() {
                    finished++;
                }

                @Override
                public void cancelled() {
                    cancelled++;
                }
            });
        }
    }
}
