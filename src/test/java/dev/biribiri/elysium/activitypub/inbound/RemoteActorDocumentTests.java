package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.jobs.PermanentJobFailure;
import dev.biribiri.elysium.actor.RemoteProfile;
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

    @Test
    void readsTheProfileAsMastodonSendsIt() {
        var profile = RemoteActorDocument.read(ACTOR, document("""
                ,"name":"Bob :blob:","summary":"<p>Hi <script>alert(1)</script><a href=\\"https://bob.test\\">there</a></p>",
                 "url":"https://remote.test/@bob","manuallyApprovesFollowers":true,"indexable":true,
                 "published":"2020-03-04T05:06:07Z",
                 "icon":{"type":"Image","mediaType":"image/png","url":"https://media.remote.test/a.png"},
                 "image":{"type":"Image","url":"https://media.remote.test/h.png"},
                 "attachment":[{"type":"PropertyValue","name":"Site","value":"<a href=\\"https://bob.test\\">bob.test</a>"},
                               {"type":"IdentityProof","name":"x","signatureValue":"y"}]""")).profile();

        assertThat(profile.displayName()).isEqualTo("Bob :blob:");
        assertThat(profile.noteHtml()).doesNotContain("script").contains("href=\"https://bob.test\"");
        assertThat(profile.note()).isEqualTo("Hi there");
        assertThat(profile.url()).hasToString("https://remote.test/@bob");
        assertThat(profile.manuallyApprovesFollowers()).isTrue();
        assertThat(profile.indexable()).isTrue();
        assertThat(profile.published()).hasToString("2020-03-04T05:06:07Z");
        assertThat(profile.avatarUrl()).hasToString("https://media.remote.test/a.png");
        assertThat(profile.headerUrl()).hasToString("https://media.remote.test/h.png");
        assertThat(profile.fields()).singleElement().satisfies(field -> {
            assertThat(field.name()).isEqualTo("Site");
            assertThat(field.value()).isEqualTo("bob.test");
            assertThat(field.valueHtml()).contains("href=\"https://bob.test\"");
        });
        assertThat(profile.bot()).isFalse();
        assertThat(profile.group()).isFalse();
    }

    @Test
    void readsAnEmptyProfileFromAMinimalDocument() {
        var profile = RemoteActorDocument.read(ACTOR, document("")).profile();

        assertThat(profile).isEqualTo(RemoteProfile.EMPTY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Service", "Application"})
    void readsBotsFromTheirType(String type) {
        var profile = RemoteActorDocument.read(ACTOR, json("""
                {"id":"%s","type":"%s","preferredUsername":"bob","inbox":"https://remote.test/ap/users/1/inbox"}
                """.formatted(ACTOR, type))).profile();

        assertThat(profile.bot()).isTrue();
    }

    @Test
    void readsGroupsFromTheirType() {
        var profile = RemoteActorDocument.read(ACTOR, json("""
                {"id":"%s","type":"Group","preferredUsername":"bob","inbox":"https://remote.test/ap/users/1/inbox"}
                """.formatted(ACTOR))).profile();

        assertThat(profile.group()).isTrue();
        assertThat(profile.bot()).isFalse();
    }

    @Test
    void readsImagesGivenAsUrlsLinksOrArrays() {
        var profile = RemoteActorDocument.read(ACTOR, document("""
                ,"icon":["https://media.remote.test/a.png","https://media.remote.test/b.png"],
                 "image":{"type":"Image","url":{"type":"Link","href":"https://media.remote.test/h.png"}}""")).profile();

        assertThat(profile.avatarUrl()).hasToString("https://media.remote.test/a.png");
        assertThat(profile.headerUrl()).hasToString("https://media.remote.test/h.png");
    }

    @Test
    void leavesOutImagesThatAreNotHttps() {
        var profile = RemoteActorDocument.read(ACTOR, document(
                ",\"icon\":\"http://media.remote.test/a.png\",\"image\":\"javascript:alert(1)\"")).profile();

        assertThat(profile.avatarUrl()).isNull();
        assertThat(profile.headerUrl()).isNull();
    }

    @Test
    void readsAProfileUrlFromALinkAndLeavesOutOneBrowsersCannotOpen() {
        var link = RemoteActorDocument.read(ACTOR, document("""
                ,"url":[{"type":"Link","href":"javascript:alert(1)"},{"type":"Link","href":"https://remote.test/@bob"}]"""));
        var bad = RemoteActorDocument.read(ACTOR, document(",\"url\":\"ftp://remote.test/bob\""));

        assertThat(link.profile().url()).hasToString("https://remote.test/@bob");
        assertThat(bad.profile().url()).isNull();
    }

    @Test
    void readsANameOnOneLineAndCutsItShort() {
        var name = "a\\nb  " + "x".repeat(RemoteActorDocument.MAX_DISPLAY_NAME + 10);
        var profile = RemoteActorDocument.read(ACTOR, document(",\"name\":\"" + name + "\"")).profile();

        assertThat(profile.displayName()).startsWith("a b x").doesNotContain("\n")
                .hasSize(RemoteActorDocument.MAX_DISPLAY_NAME);
    }

    @Test
    void leavesOutABioThatIsTooLong() {
        var summary = "<p>" + "x".repeat(RemoteActorDocument.MAX_NOTE_HTML) + "</p>";
        var profile = RemoteActorDocument.read(ACTOR, document(",\"summary\":\"" + summary + "\"")).profile();

        assertThat(profile.noteHtml()).isEmpty();
        assertThat(profile.note()).isEmpty();
    }

    @Test
    void keepsOnlySoManyFieldsAndLeavesOutUnusableOnes() {
        var fields = new StringBuilder("{\"type\":\"PropertyValue\",\"name\":\"\",\"value\":\"no name\"},");
        fields.append("{\"type\":\"PropertyValue\",\"name\":\"long\",\"value\":\"")
                .append("x".repeat(RemoteActorDocument.MAX_FIELD_VALUE_HTML + 1)).append("\"}");
        for (var i = 0; i < RemoteActorDocument.MAX_FIELDS + 5; i++) {
            fields.append(",{\"type\":\"PropertyValue\",\"name\":\"f").append(i).append("\",\"value\":\"v\"}");
        }
        var profile = RemoteActorDocument.read(ACTOR, document(",\"attachment\":[" + fields + "]")).profile();

        assertThat(profile.fields()).hasSize(RemoteActorDocument.MAX_FIELDS);
        assertThat(profile.fields().getFirst().name()).isEqualTo("f0");
    }

    @Test
    void readsTheProfilesEmoji() {
        var emoji = RemoteActorDocument.emoji(document("""
                ,"tag":[{"type":"Emoji","name":":blob:","icon":{"type":"Image","url":"https://media.remote.test/blob.png"}},
                        {"type":"Hashtag","name":"#tag"}]"""));

        assertThat(emoji).singleElement().satisfies(found -> {
            assertThat(found.shortcode()).isEqualTo("blob");
            assertThat(found.url()).hasToString("https://media.remote.test/blob.png");
        });
    }

    private static void assertRefused(JsonNode document) {
        assertThatThrownBy(() -> RemoteActorDocument.read(ACTOR, document)).isInstanceOf(PermanentJobFailure.class);
    }

    @Test
    void readsAliasesOnAnyServer() {
        var profile = RemoteActorDocument.read(ACTOR, document("""
                ,"alsoKnownAs":["https://old.test/users/bob",{"id":"https://older.test/@bob"},
                 "https://old.test/users/bob","http://plain.test/bob","not a uri","%s"]""".formatted(ACTOR))).profile();

        assertThat(profile.alsoKnownAs()).containsExactly(
                URI.create("https://old.test/users/bob"), URI.create("https://older.test/@bob"));
    }

    @Test
    void readsOneAliasThatIsntInAnArray() {
        var profile = RemoteActorDocument.read(ACTOR, document(",\"alsoKnownAs\":\"https://old.test/users/bob\"")).profile();

        assertThat(profile.alsoKnownAs()).containsExactly(URI.create("https://old.test/users/bob"));
    }

    @Test
    void keepsAtMostTwentyAliases() {
        var aliases = new StringBuilder();
        for (var i = 0; i < 30; i++) {
            aliases.append(i == 0 ? "" : ",").append("\"https://old.test/users/").append(i).append('"');
        }

        var profile = RemoteActorDocument.read(ACTOR, document(",\"alsoKnownAs\":[" + aliases + "]")).profile();

        assertThat(profile.alsoKnownAs()).hasSize(RemoteActorDocument.MAX_ALIASES);
    }

    @Test
    void keepsTheAliasAMoveIsCheckedForPastTheCap() {
        var aliases = new StringBuilder();
        for (var i = 0; i < 30; i++) {
            aliases.append(i == 0 ? "" : ",").append("\"https://old.test/users/").append(i).append('"');
        }
        var checked = URI.create("https://old.test/users/25");

        var profile = RemoteActorDocument.read(ACTOR, document(",\"alsoKnownAs\":[" + aliases + "]"), checked).profile();

        assertThat(profile.alsoKnownAs()).hasSize(RemoteActorDocument.MAX_ALIASES).contains(checked);
        assertThat(RemoteActorDocument.read(ACTOR, document(",\"alsoKnownAs\":[" + aliases + "]"),
                URI.create("https://old.test/users/99")).profile().alsoKnownAs())
                .doesNotContain(URI.create("https://old.test/users/99"));
    }

    @Test
    void readsWhereTheActorMovedTo() {
        assertThat(RemoteActorDocument.read(ACTOR, document(",\"movedTo\":\"https://new.test/users/bob\"")).profile()
                .movedTo()).isEqualTo(URI.create("https://new.test/users/bob"));
        assertThat(RemoteActorDocument.read(ACTOR, document(",\"movedTo\":{\"id\":\"https://new.test/users/bob\"}"))
                .profile().movedTo()).isEqualTo(URI.create("https://new.test/users/bob"));
        assertThat(RemoteActorDocument.read(ACTOR, document(",\"movedTo\":\"" + ACTOR + "\"")).profile().movedTo())
                .isNull();
        assertThat(RemoteActorDocument.read(ACTOR, document("")).profile().movedTo()).isNull();
    }

    private static JsonNode document(String extra) {
        return json("{\"id\":\"" + ACTOR + "\",\"type\":\"Person\",\"preferredUsername\":\"bob\","
                + "\"inbox\":\"https://remote.test/ap/users/1/inbox\"" + extra + "}");
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }
}
