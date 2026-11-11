package dev.biribiri.elysium.activitypub.inbound;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityObjectTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ID = "https://a.test/1";

    @ParameterizedTest
    @ValueSource(strings = {
            "\"https://a.test/1\"",
            "[\"https://a.test/1\"]",
            "{\"id\":\"https://a.test/1\"}",
            "[{\"id\":\"https://a.test/1\"}]",
    })
    void readsTheIdFromEveryForm(String object) {
        assertThat(parse(object)).get().extracting(ActivityObject::id).isEqualTo(ID);
    }

    @Test
    void readsAnEmbeddedObject() {
        var object = parse("""
                {"id":"https://a.test/1","type":["Follow","Other"],"actor":{"id":"https://a.test/u"},"object":["https://b.test/v"]}""");

        assertThat(object).get().extracting(
                        ActivityObject::id, ActivityObject::type, ActivityObject::actor, ActivityObject::object)
                .containsExactly(ID, "Follow", "https://a.test/u", "https://b.test/v");
    }

    @Test
    void readsAnEmbeddedObjectWithoutAnId() {
        var object = parse("{\"type\":\"Follow\",\"object\":\"https://b.test/v\"}");

        assertThat(object).get().extracting(
                        ActivityObject::id, ActivityObject::type, ActivityObject::actor, ActivityObject::object)
                .containsExactly(null, "Follow", null, "https://b.test/v");
    }

    @Test
    void hasOnlyTheIdOfAnObjectSentAsOne() {
        var object = parse("\"https://a.test/1\"").orElseThrow();

        assertThat(object.attributedTo()).isEmpty();
        assertThat(object.content()).isNull();
        assertThat(object.sensitive()).isFalse();
        assertThat(object.tags()).isEmpty();
        assertThat(object.addressing().all()).isEmpty();
    }

    @Test
    void readsTheContentOfAnEmbeddedObject() {
        var object = parse("""
                {"id":"https://a.test/1","type":"Note","attributedTo":[{"id":"https://a.test/u"},"https://a.test/g"],
                 "contentMap":{"en":"<p>Hi</p>"},"summary":"cw","sensitive":true,
                 "tag":{"type":"Mention","href":"https://b.test/v","name":"@v@b.test"},
                 "to":"as:Public","cc":["https://a.test/u/followers"]}""").orElseThrow();

        assertThat(object.attributedTo()).containsExactly("https://a.test/u", "https://a.test/g");
        assertThat(object.content()).isEqualTo("<p>Hi</p>");
        assertThat(object.summary()).isEqualTo("cw");
        assertThat(object.sensitive()).isTrue();
        assertThat(object.tags()).containsExactly(new ActivityJson.Tag("Mention", "https://b.test/v", "@v@b.test", List.of()));
        assertThat(object.addressing().to()).containsExactly("https://www.w3.org/ns/activitystreams#Public");
        assertThat(object.addressing().cc()).containsExactly("https://a.test/u/followers");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[\"https://a.test/1\",\"https://a.test/2\"]", "1", "null", "\"\"", "\"a\\u0000b\""})
    void hasNothingWhenThereIsNotOneObject(String object) {
        assertThat(parse(object)).isEmpty();
    }

    @Test
    void hasNothingWhenThereIsNoObject() {
        assertThat(ActivityObject.of("{\"id\":\"https://a.test/1\"}", JSON)).isEmpty();
    }

    @Test
    void anObjectThatDoesNotSayWhoItIsFromCouldBeFromAnyone() {
        assertThat(parse("\"https://a.test/1\"").orElseThrow().couldBeFrom("https://a.test/u")).isTrue();
        assertThat(parse("{\"id\":\"https://a.test/1\"}").orElseThrow().couldBeFrom("https://a.test/u")).isTrue();
    }

    @Test
    void anObjectThatSaysWhoItIsFromHasToMatch() {
        var object = parse("{\"actor\":\"https://a.test/u\"}").orElseThrow();

        assertThat(object.couldBeFrom("https://a.test/u")).isTrue();
        assertThat(object.couldBeFrom("https://a.test/v")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\"https://a.test/u\",\"https://a.test/v\"]", "[]", "1", "{}"})
    void anActorThatIsNotOneIdMatchesNobody(String actor) {
        var object = parse("{\"actor\":" + actor + "}").orElseThrow();

        assertThat(object.couldBeFrom("https://a.test/u")).isFalse();
    }

    private static Optional<ActivityObject> parse(String object) {
        return ActivityObject.of("{\"object\":" + object + "}", JSON);
    }
}
