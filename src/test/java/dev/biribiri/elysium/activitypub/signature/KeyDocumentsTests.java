package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.http.BlockedTargetException;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient.Response;
import dev.biribiri.elysium.actor.InstanceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KeyDocumentsTests {

    private static final InstanceProperties INSTANCE = new InstanceProperties(URI.create("https://example.test"));
    private static final String ACTIVITY_JSON = "application/activity+json";
    private static final String ACTOR = "https://remote.example/users/bob";
    private static final String KEY_ID = ACTOR + "#main-key";
    private static final String KEY_DOCUMENT = "https://remote.example/keys/1";
    private static final String STUB = ACTOR + "/main-key";
    private static final String PEM = jsonString(SignatureFixtures.PUBLIC_KEY_PEM);

    private final SignedFetcher http = mock();
    private final KeyDocuments documents = new KeyDocuments(new ActivityDocuments(http, JsonMapper.builder().build()));

    @Test
    void readsAKeyFromAnActor() throws Exception {
        serve(ACTOR, actorWith(keyObject(KEY_ID, ACTOR)));

        assertThat(documents.fetch(keyId(KEY_ID)))
                .isEqualTo(new ResolvedKey(KEY_ID, URI.create(ACTOR), SignatureFixtures.publicKey()));
    }

    @Test
    void readsAKeyFromAnArray() throws Exception {
        var other = "{\"id\":\"" + ACTOR + "#other\",\"publicKeyPem\":\"unused\"}";
        serve(ACTOR, actorWith("[" + other + "," + keyObject(KEY_ID, ACTOR) + "]"));

        assertThat(documents.fetch(keyId(KEY_ID)).key()).isEqualTo(SignatureFixtures.publicKey());
    }

    @Test
    void allowsAKeyWithoutAnOwnerInItsActor() throws Exception {
        serve(ACTOR, actorWith("{\"id\":\"" + KEY_ID + "\",\"publicKeyPem\":\"" + PEM + "\"}"));

        assertThat(documents.fetch(keyId(KEY_ID)).owner()).hasToString(ACTOR);
    }

    @Test
    void allowsAKeyInAnActorToGiveItsOwnerAsAnObject() throws Exception {
        serve(ACTOR, actorWith("{\"id\":\"%s\",\"owner\":{\"id\":\"%s\"},\"publicKeyPem\":\"%s\"}"
                .formatted(KEY_ID, ACTOR, PEM)));

        assertThat(documents.fetch(keyId(KEY_ID)).owner()).hasToString(ACTOR);
    }

    @Test
    void rejectsAKeyInAnActorWhoseOwnerIsAnObjectForAnotherActor() throws Exception {
        serve(ACTOR, actorWith("{\"id\":\"%s\",\"owner\":{\"id\":\"%s\"},\"publicKeyPem\":\"%s\"}"
                .formatted(KEY_ID, "https://remote.example/users/mallory", PEM)));

        assertPermanent(KEY_ID);
    }

    @Test
    void acceptsAnActorIdAsAKeyIdIfItHasOneKey() throws Exception {
        serve(ACTOR, actorWith(keyObject(KEY_ID, ACTOR)));

        var key = documents.fetch(keyId(ACTOR));

        assertThat(key.keyId()).isEqualTo(ACTOR);
        assertThat(key.owner()).hasToString(ACTOR);
    }

    @Test
    void doesNotGuessAKeyIfAnActorHasSeveral() throws Exception {
        serve(ACTOR, actorWith("[" + keyObject(KEY_ID, ACTOR) + "," + keyObject(ACTOR + "#other", ACTOR) + "]"));

        assertPermanent(ACTOR);
    }

    @Test
    void rejectsAFragmentTheActorDoesNotHave() throws Exception {
        serve(ACTOR, actorWith(keyObject(KEY_ID, ACTOR)));

        assertPermanent(ACTOR + "#other");
    }

    @Test
    void rejectsAKeyInAnActorThatNamesAnotherOwner() throws Exception {
        serve(ACTOR, actorWith(keyObject(KEY_ID, "https://remote.example/users/mallory")));

        assertPermanent(KEY_ID);
    }

    @Test
    void rejectsAnActorThatOnlyRefersToTheKey() throws Exception {
        serve(ACTOR, actorWith("\"" + KEY_ID + "\""));

        assertPermanent(KEY_ID);
    }

    @Test
    void rejectsDocumentsThatArentActors() throws Exception {
        serve(ACTOR, "{\"id\":\"%s\",\"type\":\"Note\",\"publicKey\":%s}".formatted(ACTOR, keyObject(KEY_ID, ACTOR)));

        assertPermanent(KEY_ID);
    }

    @Test
    void acceptsAnActorWithSeveralTypes() throws Exception {
        serve(ACTOR, "{\"id\":\"%s\",\"type\":[\"Extra\",\"Service\"],\"publicKey\":%s}"
                .formatted(ACTOR, keyObject(KEY_ID, ACTOR)));

        assertThat(documents.fetch(keyId(KEY_ID)).owner()).hasToString(ACTOR);
    }

    /**
     * The document is consistent with itself, so only comparing its ID to its URL can catch this.
     */
    @Test
    void rejectsADocumentWithADifferentId() throws Exception {
        var other = "https://other.example/users/bob";
        serve(ACTOR, "{\"id\":\"%s\",\"type\":\"Person\",\"publicKey\":%s}"
                .formatted(other, keyObject(KEY_ID, other)));

        assertPermanent(KEY_ID);
        verify(http, never()).get(eq(URI.create(other)), any());
    }

    @Test
    void followsAStubToItsActor() throws Exception {
        serve(STUB, stubOf(ACTOR, keyObject(STUB, ACTOR)));
        serve(ACTOR, actorWith(keyObject(STUB, ACTOR)));

        assertThat(documents.fetch(keyId(STUB)))
                .isEqualTo(new ResolvedKey(STUB, URI.create(ACTOR), SignatureFixtures.publicKey()));
    }

    @Test
    void ignoresTheKeyInAStub() throws Exception {
        var other = jsonString(SignatureFixtures.newPublicKeyPem(2048));
        serve(STUB, stubOf(ACTOR, "{\"id\":\"%s\",\"owner\":\"%s\",\"publicKeyPem\":\"%s\"}".formatted(STUB, ACTOR, other)));
        serve(ACTOR, actorWith(keyObject(STUB, ACTOR)));

        assertThat(documents.fetch(keyId(STUB)).key()).isEqualTo(SignatureFixtures.publicKey());
    }

    /**
     * This is what someone could try if they can serve documents on a shared host: claiming that their own key belongs
     * to someone else there.
     */
    @Test
    void rejectsAStubForAnActorThatDoesNotListTheKey() throws Exception {
        serve(STUB, stubOf(ACTOR, keyObject(STUB, ACTOR)));
        serve(ACTOR, actorWith(keyObject(KEY_ID, ACTOR)));

        assertPermanent(STUB);
    }

    @Test
    void doesNotAcceptAStubBecauseItsActorHasOneKey() throws Exception {
        serve(STUB, stubOf(ACTOR, keyObject(STUB, ACTOR)));
        serve(ACTOR, "{\"id\":\"%s\",\"type\":\"Person\",\"publicKey\":{\"id\":\"%s\",\"publicKeyPem\":\"%s\"}}"
                .formatted(ACTOR, ACTOR, PEM));

        assertPermanent(STUB);
    }

    @Test
    void followsOnlyOneStep() throws Exception {
        var third = "https://remote.example/users/carol";
        serve(STUB, stubOf(ACTOR, keyObject(STUB, ACTOR)));
        serve(ACTOR, stubOf(third, keyObject(STUB, third)));
        serve(third, "{\"id\":\"%s\",\"type\":\"Person\",\"publicKey\":%s}".formatted(third, keyObject(STUB, third)));

        assertPermanent(STUB);
        verify(http, never()).get(eq(URI.create(third)), any());
    }

    @Test
    void doesNotFollowAStubToADocumentThatIsNotAnActor() throws Exception {
        serve(STUB, stubOf(KEY_DOCUMENT, keyObject(STUB, ACTOR)));
        serve(KEY_DOCUMENT, "{\"id\":\"%s\",\"owner\":\"%s\",\"publicKeyPem\":\"%s\"}".formatted(KEY_DOCUMENT, ACTOR, PEM));

        assertPermanent(STUB);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://remote.example/users/bob",
            "https://remote.example:8443/users/bob",
            "https://sub.remote.example/users/bob",
            "https://user@remote.example/users/bob",
            "https://remote.example/users/bob#fragment",
            "not a uri",
            "/users/bob"
    })
    void neverFollowsAStubToAnUnsuitableUrl(String id) throws Exception {
        serve(STUB, stubOf(id, keyObject(STUB, ACTOR)));

        assertPermanent(STUB);
        verify(http, times(1)).get(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\"id\":null,", "\"id\":1,"})
    void rejectsADocumentWithoutAUsableId(String id) throws Exception {
        serve(STUB, "{%s\"type\":\"Person\",\"publicKey\":%s}".formatted(id, keyObject(STUB, ACTOR)));

        assertPermanent(STUB);
        verify(http, times(1)).get(any(), any());
    }

    @Test
    void rejectsAStubWithTheWrongContentType() throws Exception {
        serve(STUB, "application/json", stubOf(ACTOR, keyObject(STUB, ACTOR)));

        assertPermanent(STUB);
        verify(http, never()).get(eq(URI.create(ACTOR)), any());
    }

    @Test
    void treatsAStubsActorBeingDownAsTemporary() throws Exception {
        serve(STUB, stubOf(ACTOR, keyObject(STUB, ACTOR)));
        when(http.get(eq(URI.create(ACTOR)), any())).thenThrow(new IOException("down"));

        assertTemporary(STUB);
    }

    @Test
    void rejectsADocumentWithoutAKey() throws Exception {
        serve(ACTOR, "{\"id\":\"%s\",\"type\":\"Person\"}".formatted(ACTOR));

        assertPermanent(KEY_ID);
    }

    @Test
    void rejectsAKeyThatIsTooSmall() throws Exception {
        var small = jsonString(SignatureFixtures.newPublicKeyPem(1024));
        serve(ACTOR, actorWith("{\"id\":\"%s\",\"owner\":\"%s\",\"publicKeyPem\":\"%s\"}"
                .formatted(KEY_ID, ACTOR, small)));

        assertPermanent(KEY_ID);
    }

    @Test
    void readsAKeyDocumentWhoseOwnerListsIt() throws Exception {
        serve(KEY_DOCUMENT, keyDocument(ACTOR));
        serve(ACTOR, actorWith("\"" + KEY_DOCUMENT + "\""));

        assertThat(documents.fetch(keyId(KEY_DOCUMENT)))
                .isEqualTo(new ResolvedKey(KEY_DOCUMENT, URI.create(ACTOR), SignatureFixtures.publicKey()));
    }

    @Test
    void readsAKeyDocumentWhoseOwnerIsAnObject() throws Exception {
        serve(KEY_DOCUMENT, "{\"id\":\"%s\",\"owner\":{\"id\":\"%s\"},\"publicKeyPem\":\"%s\"}"
                .formatted(KEY_DOCUMENT, ACTOR, PEM));
        serve(ACTOR, actorWith("[\"" + KEY_DOCUMENT + "\"]"));

        assertThat(documents.fetch(keyId(KEY_DOCUMENT)).owner()).hasToString(ACTOR);
    }

    @Test
    void readsAKeyDocumentWhoseOwnerIncludesTheSameKey() throws Exception {
        serve(KEY_DOCUMENT, keyDocument(ACTOR));
        serve(ACTOR, actorWith(keyObject(KEY_DOCUMENT, ACTOR)));

        assertThat(documents.fetch(keyId(KEY_DOCUMENT)).owner()).hasToString(ACTOR);
    }

    @Test
    void rejectsAKeyDocumentWhoseOwnerHasADifferentKey() throws Exception {
        var other = jsonString(SignatureFixtures.newPublicKeyPem(2048));
        serve(KEY_DOCUMENT, keyDocument(ACTOR));
        serve(ACTOR, actorWith("{\"id\":\"%s\",\"publicKeyPem\":\"%s\"}".formatted(KEY_DOCUMENT, other)));

        assertPermanent(KEY_DOCUMENT);
    }

    @Test
    void rejectsAKeyDocumentWhoseOwnerDoesNotListIt() throws Exception {
        serve(KEY_DOCUMENT, keyDocument(ACTOR));
        serve(ACTOR, actorWith(keyObject(KEY_ID, ACTOR)));

        assertPermanent(KEY_DOCUMENT);
    }

    @Test
    void rejectsAKeyDocumentWithoutAnOwner() throws Exception {
        serve(KEY_DOCUMENT, "{\"id\":\"%s\",\"publicKeyPem\":\"%s\"}".formatted(KEY_DOCUMENT, PEM));

        assertPermanent(KEY_DOCUMENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://other.example/users/bob",
            "http://remote.example/users/bob",
            "https://remote.example:8443/users/bob",
            "https://sub.remote.example/users/bob"
    })
    void neverFetchesAnOwnerOnAnotherServer(String owner) throws Exception {
        serve(KEY_DOCUMENT, keyDocument(owner));

        assertPermanent(KEY_DOCUMENT);
        verify(http, never()).get(eq(URI.create(owner)), any());
    }

    @Test
    void rejectsAFragmentThatPointsIntoAKeyDocument() throws Exception {
        serve(KEY_DOCUMENT, keyDocument(ACTOR));

        assertPermanent(KEY_DOCUMENT + "#fragment");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "application/json",
            "text/html",
            "application/ld+json",
            "application/ld+json; profile=\"https://example.test/other\"",
            "application/*+json",
            "not a type"
    })
    void rejectsOtherContentTypes(String contentType) throws Exception {
        serve(ACTOR, contentType, actorWith(keyObject(KEY_ID, ACTOR)));

        assertPermanent(KEY_ID);
    }

    @Test
    void rejectsAMissingContentType() throws Exception {
        serve(ACTOR, null, actorWith(keyObject(KEY_ID, ACTOR)));

        assertPermanent(KEY_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "application/activity+json; charset=utf-8",
            "APPLICATION/ACTIVITY+JSON",
            "application/ld+json; profile=\"https://www.w3.org/ns/activitystreams\"",
            "application/ld+json; charset=utf-8; profile=\"https://www.w3.org/ns/activitystreams\""
    })
    void acceptsActivityPubContentTypes(String contentType) throws Exception {
        serve(ACTOR, contentType, actorWith(keyObject(KEY_ID, ACTOR)));

        assertThat(documents.fetch(keyId(KEY_ID)).owner()).hasToString(ACTOR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "\"text\"", "{", "<html></html>"})
    void rejectsBodiesThatArentJsonObjects(String body) throws Exception {
        serve(ACTOR, ACTIVITY_JSON, body);

        assertPermanent(KEY_ID);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 400, 401, 403})
    void treatsOtherAnswersAsPermanent(int status) throws Exception {
        when(http.get(eq(URI.create(ACTOR)), any())).thenReturn(new Response(status, ACTIVITY_JSON, null, new byte[0]));

        assertPermanent(KEY_ID);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 410})
    void treatsMissingDocumentsAsGone(int status) throws Exception {
        when(http.get(eq(URI.create(ACTOR)), any())).thenReturn(new Response(status, ACTIVITY_JSON, null, new byte[0]));

        assertThatThrownBy(() -> documents.fetch(keyId(KEY_ID)))
                .isInstanceOfSatisfying(DocumentFetchException.class, e -> assertThat(e.isGone()).isTrue());
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 502, 503, 504})
    void treatsOverloadedServersAsTemporary(int status) throws Exception {
        when(http.get(eq(URI.create(ACTOR)), any())).thenReturn(new Response(status, ACTIVITY_JSON, null, new byte[0]));

        assertTemporary(KEY_ID);
    }

    @Test
    void treatsNetworkFailuresAsTemporary() throws Exception {
        when(http.get(eq(URI.create(ACTOR)), any())).thenThrow(new SocketTimeoutException("slow"));

        assertTemporary(KEY_ID);
    }

    @Test
    void treatsAKeyDocumentsOwnerBeingDownAsTemporary() throws Exception {
        serve(KEY_DOCUMENT, keyDocument(ACTOR));
        when(http.get(eq(URI.create(ACTOR)), any())).thenThrow(new IOException("down"));

        assertTemporary(KEY_DOCUMENT);
    }

    @Test
    void treatsBlockedAddressesAsPermanent() throws Exception {
        when(http.get(eq(URI.create(ACTOR)), any())).thenThrow(mock(BlockedTargetException.class));

        assertPermanent(KEY_ID);
    }

    private void assertPermanent(String id) {
        assertThatThrownBy(() -> documents.fetch(keyId(id)))
                .isInstanceOfSatisfying(DocumentFetchException.class, e -> {
                    assertThat(e.isTemporary()).isFalse();
                    assertThat(e.isGone()).isFalse();
                });
    }

    private void assertTemporary(String id) {
        assertThatThrownBy(() -> documents.fetch(keyId(id)))
                .isInstanceOfSatisfying(DocumentFetchException.class, e -> assertThat(e.isTemporary()).isTrue());
    }

    private void serve(String url, String body) throws Exception {
        serve(url, ACTIVITY_JSON, body);
    }

    private void serve(String url, String contentType, String body) throws Exception {
        when(http.get(eq(URI.create(url)), any()))
                .thenReturn(new Response(200, contentType, null, body.getBytes(StandardCharsets.UTF_8)));
    }

    private static KeyId keyId(String value) {
        return KeyId.parse(value, INSTANCE);
    }

    private static String actorWith(String publicKey) {
        return "{\"id\":\"%s\",\"type\":\"Person\",\"publicKey\":%s}".formatted(ACTOR, publicKey);
    }

    private static String stubOf(String id, String publicKey) {
        return "{\"id\":\"%s\",\"type\":\"Person\",\"publicKey\":%s}".formatted(id, publicKey);
    }

    private static String keyObject(String id, String owner) {
        return "{\"id\":\"%s\",\"owner\":\"%s\",\"publicKeyPem\":\"%s\"}".formatted(id, owner, PEM);
    }

    private static String keyDocument(String owner) {
        return "{\"id\":\"%s\",\"owner\":\"%s\",\"publicKeyPem\":\"%s\"}".formatted(KEY_DOCUMENT, owner, PEM);
    }

    private static String jsonString(String text) {
        return text.replace("\n", "\\n");
    }
}
