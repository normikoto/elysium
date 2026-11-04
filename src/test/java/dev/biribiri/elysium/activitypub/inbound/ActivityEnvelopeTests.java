package dev.biribiri.elysium.activitypub.inbound;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityEnvelopeTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void readsAnActivityAndKeepsItsExactBody() {
        var body = "{ \"id\":\"https://a.test/1\",  \"type\":\"Create\", \"actor\":\"https://a.test/u\" }";

        assertThat(parse(body)).contains(new ActivityEnvelope("https://a.test/1", "Create", "https://a.test/u", body));
    }

    @Test
    void readsAnActorSentAsAnObject() {
        var parsed = parse("{\"id\":\"https://a.test/1\",\"type\":\"Create\",\"actor\":{\"id\":\"https://a.test/u\"}}");

        assertThat(parsed).get().extracting(ActivityEnvelope::actor).isEqualTo("https://a.test/u");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\"https://a.test/u\"]", "[{\"id\":\"https://a.test/u\"}]"})
    void readsAnArrayOfOneActor(String actor) {
        var parsed = parse("{\"id\":\"https://a.test/1\",\"type\":\"Create\",\"actor\":" + actor + "}");

        assertThat(parsed).get().extracting(ActivityEnvelope::actor).isEqualTo("https://a.test/u");
    }

    @Test
    void hasNoSingleActorWhenThereAreSeveral() {
        var parsed = parse("{\"id\":\"https://a.test/1\",\"type\":\"Create\",\"actor\":[\"https://a.test/u\",\"https://a.test/v\"]}");

        assertThat(parsed).get().extracting(ActivityEnvelope::actor).isNull();
    }

    @Test
    void readsTheFirstOfSeveralTypes() {
        var parsed = parse("{\"id\":\"https://a.test/1\",\"type\":[1,\"Create\",\"Other\"],\"actor\":\"https://a.test/u\"}");

        assertThat(parsed).get().extracting(ActivityEnvelope::type).isEqualTo("Create");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not json",
            "[]",
            "\"text\"",
            "{}",
            "{\"type\":\"Create\",\"actor\":\"https://a.test/u\"}",
            "{\"id\":\"https://a.test/1\",\"actor\":\"https://a.test/u\"}",
            "{\"id\":\"https://a.test/1\",\"type\":\"Create\"}",
            "{\"id\":1,\"type\":\"Create\",\"actor\":\"https://a.test/u\"}",
            "{\"id\":\"\",\"type\":\"Create\",\"actor\":\"https://a.test/u\"}",
            "{\"id\":\"https://a.test/1\",\"type\":[],\"actor\":\"https://a.test/u\"}",
            "{\"id\":\"https://a.test/1\",\"type\":\"Create\",\"actor\":{}}",
            "{\"id\":\"https://a.test/1\",\"type\":\"Create\",\"actor\":[]}",
            "{\"id\":\"https://a.test/1\",\"type\":\"Create\",\"actor\":[1]}",
            "{\"id\":\"https://a.test/1\",\"type\":\"Create\",\"actor\":[[\"https://a.test/u\"]]}",
            // Spelled-out NUL characters, which the database can't store in a column
            "{\"id\":\"https://a.test/\\u0000\",\"type\":\"Create\",\"actor\":\"https://a.test/u\"}",
            "{\"id\":\"https://a.test/1\",\"type\":\"Cre\\u0000ate\",\"actor\":\"https://a.test/u\"}",
    })
    void refusesWhatIsNotAnActivity(String body) {
        assertThat(parse(body)).isEmpty();
    }

    @Test
    void refusesInvalidUtf8() {
        var body = "{\"id\":\"https://a.test/1\",\"type\":\"Create\",\"actor\":\"https://a.test/u\",\"x\":\"#\"}"
                .getBytes(StandardCharsets.UTF_8);
        body[body.length - 3] = (byte) 0xff;

        assertThat(ActivityEnvelope.parse(body, JSON)).isEmpty();
    }

    /**
     * Jackson would read this if it were given the bytes, and the body would then have NUL characters in it.
     */
    @Test
    void refusesUtf16() {
        var body = "{\"id\":\"https://a.test/1\",\"type\":\"Create\",\"actor\":\"https://a.test/u\"}"
                .getBytes(StandardCharsets.UTF_16BE);

        assertThat(ActivityEnvelope.parse(body, JSON)).isEmpty();
    }

    private static Optional<ActivityEnvelope> parse(String body) {
        return ActivityEnvelope.parse(body.getBytes(StandardCharsets.UTF_8), JSON);
    }
}
