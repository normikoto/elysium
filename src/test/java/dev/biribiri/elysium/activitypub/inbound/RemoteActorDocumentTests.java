package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteActorDocumentTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final URI ACTOR = URI.create("https://remote.test/ap/users/1");

    @Test
    void readsACompleteDocument() {
        var profile = RemoteActorDocument.read(ACTOR, json("""
                {"id":"https://remote.test/ap/users/1","type":"Person","preferredUsername":"bob",
                 "inbox":"https://remote.test/ap/users/1/inbox","outbox":"https://remote.test/ap/users/1/outbox",
                 "endpoints":{"sharedInbox":"https://remote.test/inbox"},"followers":"https://remote.test/ap/users/1/followers",
                 "publicKey":{"id":"https://remote.test/ap/users/1#main-key","publicKeyPem":"PEM"}}
                """));

        assertThat(profile.uri()).isEqualTo(ACTOR);
        assertThat(profile.username()).isEqualTo("bob");
        assertThat(profile.domain()).isEqualTo("remote.test");
        assertThat(profile.inboxUri()).hasToString("https://remote.test/ap/users/1/inbox");
        assertThat(profile.outboxUri()).hasToString("https://remote.test/ap/users/1/outbox");
        assertThat(profile.sharedInboxUri()).hasToString("https://remote.test/inbox");
        assertThat(profile.publicKeyPem()).isEqualTo("PEM");
        assertThat(profile.followersUri()).hasToString("https://remote.test/ap/users/1/followers");
    }

    @Test
    void onlyNeedsAnIdTypeUsernameAndInbox() {
        var profile = RemoteActorDocument.read(ACTOR, document(""));

        assertThat(profile.outboxUri()).isNull();
        assertThat(profile.sharedInboxUri()).isNull();
        assertThat(profile.publicKeyPem()).isNull();
        assertThat(profile.followersUri()).isNull();
    }

    @Test
    void includesThePortInTheDomainUnlessItIsTheDefault() {
        var custom = URI.create("https://REMOTE.test:8443/u");
        var standard = URI.create("https://remote.test:443/u");

        assertThat(RemoteActorDocument.domainOf(custom)).isEqualTo("remote.test:8443");
        assertThat(RemoteActorDocument.domainOf(standard)).isEqualTo("remote.test");
    }

    @Test
    void readsTheFirstKeyThatIsIncluded() {
        var profile = RemoteActorDocument.read(ACTOR, document("""
                ,"publicKey":["https://remote.test/keys/1",{"id":"https://remote.test/keys/2","publicKeyPem":"PEM"}]"""));

        assertThat(profile.publicKeyPem()).isEqualTo("PEM");
    }

    @Test
    void leavesOutAKeyThatCannotBeStored() {
        var profile = RemoteActorDocument.read(ACTOR, document(",\"publicKey\":{\"publicKeyPem\":\"a\\u0000b\"}"));

        assertThat(profile.publicKeyPem()).isNull();
    }

    @Test
    void leavesOutAnOutboxThatIsNotAUri() {
        var profile = RemoteActorDocument.read(ACTOR, document(",\"outbox\":\"not a uri\""));

        assertThat(profile.outboxUri()).isNull();
    }

    @Test
    void refusesADocumentWithADifferentId() {
        var document = json("""
                {"id":"https://remote.test/ap/users/2","type":"Person","preferredUsername":"bob",
                 "inbox":"https://remote.test/inbox"}
                """);

        assertRefused(document);
    }

    @Test
    void refusesWhatIsNotAnActor() {
        var document = json("""
                {"id":"https://remote.test/ap/users/1","type":"Note","preferredUsername":"bob",
                 "inbox":"https://remote.test/inbox"}
                """);

        assertRefused(document);
    }

    @Test
    void acceptsAnActorTypeInAnArray() {
        var profile = RemoteActorDocument.read(ACTOR, json("""
                {"id":"https://remote.test/ap/users/1","type":["Other","Service"],"preferredUsername":"bob",
                 "inbox":"https://remote.test/inbox"}
                """));

        assertThat(profile.username()).isEqualTo("bob");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"preferredUsername\":\"\"",
            "\"preferredUsername\":1",
            "\"preferredUsername\":\"a\\u0000b\"",
            "\"preferredUsername\":\"a\\nb\"",
            "\"preferredUsername\":\"a\\u009fb\"",
    })
    void refusesABadUsername(String username) {
        assertRefused(json("{\"id\":\"" + ACTOR + "\",\"type\":\"Person\",\"inbox\":\"https://remote.test/inbox\"," + username + "}"));
    }

    @Test
    void refusesAMissingUsernameAndAVeryLongOne() {
        assertRefused(json("{\"id\":\"" + ACTOR + "\",\"type\":\"Person\",\"inbox\":\"https://remote.test/inbox\"}"));
        assertRefused(json("{\"id\":\"" + ACTOR + "\",\"type\":\"Person\",\"inbox\":\"https://remote.test/inbox\","
                + "\"preferredUsername\":\"" + "a".repeat(256) + "\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://remote.test/inbox",
            "https://other.test/inbox",
            "https://remote.test:8443/inbox",
            "https://user@remote.test/inbox",
            "/inbox",
            "not a uri",
    })
    void refusesAnInboxThatIsNotHttpsOnTheActorsHost(String inbox) {
        assertRefused(json("{\"id\":\"" + ACTOR + "\",\"type\":\"Person\",\"preferredUsername\":\"bob\",\"inbox\":\"" + inbox + "\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://remote.test/inbox",
            "https://other.test/inbox",
            "https://user@remote.test/inbox",
            "not a uri",
    })
    void leavesOutASharedInboxThatIsNotHttpsOnTheActorsHost(String inbox) {
        var profile = RemoteActorDocument.read(ACTOR, document(",\"endpoints\":{\"sharedInbox\":\"" + inbox + "\"}"));

        assertThat(profile.sharedInboxUri()).isNull();
        assertThat(profile.inboxUri()).hasToString("https://remote.test/ap/users/1/inbox");
    }

    @Test
    void refusesAMissingInboxAndAVeryLongOne() {
        assertRefused(json("{\"id\":\"" + ACTOR + "\",\"type\":\"Person\",\"preferredUsername\":\"bob\"}"));
        assertRefused(json("{\"id\":\"" + ACTOR + "\",\"type\":\"Person\",\"preferredUsername\":\"bob\","
                + "\"inbox\":\"https://remote.test/" + "a".repeat(2048) + "\"}"));
    }

    @Test
    void doesNotQuoteTheDocumentInFailures() {
        var document = json("{\"id\":\"" + ACTOR + "\",\"type\":\"Person\",\"preferredUsername\":\"bob\","
                + "\"inbox\":\"https://other.test/secret\"}");

        assertThatThrownBy(() -> RemoteActorDocument.read(ACTOR, document))
                .isInstanceOf(PermanentJobFailure.class)
                .hasMessageNotContaining("secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://remote.test/f", "https://other.test/f", "https://remote.test:8443/f", "/f"})
    void leavesOutAFollowersCollectionThatIsNotHttpsOnTheActorsHost(String followers) {
        var profile = RemoteActorDocument.read(ACTOR, document(",\"followers\":\"" + followers + "\""));

        assertThat(profile.followersUri()).isNull();
    }

    private static void assertRefused(JsonNode document) {
        assertThatThrownBy(() -> RemoteActorDocument.read(ACTOR, document)).isInstanceOf(PermanentJobFailure.class);
    }

    private static JsonNode document(String extra) {
        return json("{\"id\":\"" + ACTOR + "\",\"type\":\"Person\",\"preferredUsername\":\"bob\","
                + "\"inbox\":\"https://remote.test/ap/users/1/inbox\"" + extra + "}");
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }
}
