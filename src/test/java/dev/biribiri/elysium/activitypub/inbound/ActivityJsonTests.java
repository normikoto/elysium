package dev.biribiri.elysium.activitypub.inbound;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityJsonTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {
            "\"https://a.test/1\"",
            "[\"https://a.test/1\"]",
            "{\"id\":\"https://a.test/1\"}",
            "[{\"id\":\"https://a.test/1\"},\"https://a.test/1\"]",
    })
    void readsIdsFromEveryForm(String value) {
        assertThat(ActivityJson.ids(node(value))).containsExactly("https://a.test/1");
    }

    @Test
    void keepsTheOrderOfIdsAndSkipsWhatIsNotOne() {
        var ids = ActivityJson.ids(node("[\"https://a.test/2\",1,null,\"\",{},{\"id\":3},\"a\\u0000b\",\"https://a.test/1\"]"));

        assertThat(ids).containsExactly("https://a.test/2", "https://a.test/1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "1", "{}", "[]", "true"})
    void hasNoIdsWhenThereAreNone(String value) {
        assertThat(ActivityJson.ids(node(value))).isEmpty();
    }

    @Test
    void readsEveryType() {
        assertThat(ActivityJson.types(node("\"Note\""))).containsExactly("Note");
        assertThat(ActivityJson.types(node("[\"Note\",1,\"Article\",\"Note\"]"))).containsExactly("Note", "Article");
        assertThat(ActivityJson.types(node("1"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://www.w3.org/ns/activitystreams#Public", "as:Public", "Public"})
    void writesThePublicCollectionInFull(String id) {
        assertThat(ActivityJson.audienceId(id)).isEqualTo("https://www.w3.org/ns/activitystreams#Public");
    }

    @Test
    void leavesOtherAddressesAlone() {
        assertThat(ActivityJson.audienceId("https://a.test/public")).isEqualTo("https://a.test/public");
    }

    @Test
    void prefersThePlainNaturalLanguageValue() {
        var object = node("{\"content\":\"plain\",\"contentMap\":{\"en\":\"mapped\"}}");

        assertThat(ActivityJson.naturalText(object, "content")).isEqualTo("plain");
    }

    @Test
    void fallsBackToTheMapPreferringNoLanguage() {
        assertThat(ActivityJson.naturalText(node("{\"contentMap\":{\"en\":\"en\",\"und\":\"und\"}}"), "content"))
                .isEqualTo("und");
        assertThat(ActivityJson.naturalText(node("{\"summaryMap\":{\"de\":1,\"fr\":\"fr\",\"en\":\"en\"}}"), "summary"))
                .isEqualTo("fr");
        assertThat(ActivityJson.naturalText(node("{\"content\":\"\",\"contentMap\":{\"en\":\"en\"}}"), "content"))
                .isEqualTo("en");
    }

    @Test
    void hasNoNaturalLanguageValueWhenThereIsNone() {
        assertThat(ActivityJson.naturalText(node("{}"), "content")).isNull();
        assertThat(ActivityJson.naturalText(node("{\"contentMap\":\"x\"}"), "content")).isNull();
        assertThat(ActivityJson.naturalText(node("{\"contentMap\":{\"en\":null}}"), "content")).isNull();
    }

    @Test
    void readsOnlyTrueAsAFlag() {
        assertThat(ActivityJson.flag(node("true"))).isTrue();
        assertThat(ActivityJson.flag(node("false"))).isFalse();
        assertThat(ActivityJson.flag(node("\"true\""))).isFalse();
        assertThat(ActivityJson.flag(JSON.readTree("{}").path("sensitive"))).isFalse();
    }

    @Test
    void readsTagsFromOneOrAnArray() {
        var mention = new ActivityJson.Tag("Mention", "https://b.test/v", "@v@b.test", List.of());
        var single = "{\"type\":\"Mention\",\"href\":\"https://b.test/v\",\"name\":\"@v@b.test\"}";

        assertThat(ActivityJson.tags(node(single))).containsExactly(mention);
        assertThat(ActivityJson.tags(node("[" + single + ",\"https://c.test/t\",{\"type\":[\"Hashtag\"],\"name\":\"#x\"}]")))
                .containsExactly(mention, new ActivityJson.Tag("Hashtag", null, "#x", List.of()));
        assertThat(ActivityJson.tags(JSON.readTree("{}").path("tag"))).isEmpty();
    }

    private static JsonNode node(String value) {
        return JSON.readTree(value);
    }
}
