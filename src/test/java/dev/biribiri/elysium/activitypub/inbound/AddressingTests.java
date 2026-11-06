package dev.biribiri.elysium.activitypub.inbound;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AddressingTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PUBLIC = "https://www.w3.org/ns/activitystreams#Public";

    @Test
    void readsEachProperty() {
        var addressing = Addressing.of(JSON.readTree("""
                {"to":"https://a.test/1","cc":["https://a.test/2",{"id":"https://a.test/3"}],"bto":["https://a.test/4"],
                 "bcc":"https://a.test/5","audience":{"id":"https://a.test/6"}}"""));

        assertThat(addressing.to()).containsExactly("https://a.test/1");
        assertThat(addressing.cc()).containsExactlyInAnyOrder("https://a.test/2", "https://a.test/3");
        assertThat(addressing.bto()).containsExactly("https://a.test/4");
        assertThat(addressing.bcc()).containsExactly("https://a.test/5");
        assertThat(addressing.audience()).containsExactly("https://a.test/6");
        assertThat(addressing.all()).hasSize(6);
    }

    @Test
    void isPublicWhenThePublicCollectionIsInToOrCc() {
        assertThat(Addressing.of(JSON.readTree("{\"to\":\"Public\"}")).isPublic()).isTrue();
        assertThat(Addressing.of(JSON.readTree("{\"cc\":[\"as:Public\"]}")).isPublic()).isTrue();
        assertThat(Addressing.of(JSON.readTree("{\"to\":\"" + PUBLIC + "\"}")).isPublic()).isTrue();
        assertThat(Addressing.of(JSON.readTree("{\"bcc\":\"" + PUBLIC + "\"}")).isPublic()).isFalse();
        assertThat(Addressing.of(JSON.readTree("{}")).isPublic()).isFalse();
    }

    @Test
    void includesTheEmbeddedObjectsAddressing() {
        var addressing = Addressing.ofActivity(JSON.readTree("""
                {"to":"https://a.test/1","object":[{"to":"https://a.test/2","cc":"Public"}]}"""));

        assertThat(addressing.to()).containsExactlyInAnyOrder("https://a.test/1", "https://a.test/2");
        assertThat(addressing.cc()).containsExactly(PUBLIC);
    }

    @Test
    void ignoresAnObjectSentAsAnId() {
        var addressing = Addressing.ofActivity(JSON.readTree("{\"to\":\"https://a.test/1\",\"object\":\"https://a.test/2\"}"));

        assertThat(addressing.all()).containsExactly("https://a.test/1");
    }
}
