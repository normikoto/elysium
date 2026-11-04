package dev.biribiri.elysium.activitypub.inbound;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

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

        assertThat(object).contains(new ActivityObject(ID, "Follow", "https://a.test/u", "https://b.test/v"));
    }

    @Test
    void readsAnEmbeddedObjectWithoutAnId() {
        var object = parse("{\"type\":\"Follow\",\"object\":\"https://b.test/v\"}");

        assertThat(object).contains(new ActivityObject(null, "Follow", null, "https://b.test/v"));
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
