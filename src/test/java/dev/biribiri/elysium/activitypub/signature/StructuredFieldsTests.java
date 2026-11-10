package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException.Failure;
import dev.biribiri.elysium.activitypub.signature.StructuredFields.InnerList;
import dev.biribiri.elysium.activitypub.signature.StructuredFields.Item;
import dev.biribiri.elysium.activitypub.signature.StructuredFields.Token;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredFieldsTests {

    @Test
    void readsADictionaryOfInnerListsWithParameters() {
        var dictionary = StructuredFields.parseDictionary(
                "sig1=(\"@method\" \"@target-uri\");created=1618884475;keyid=\"test-key\", sig2=();alg=tok");

        assertThat(dictionary.sequencedKeySet()).containsExactly("sig1", "sig2");
        var first = (InnerList) dictionary.get("sig1");
        assertThat(first.items()).extracting(Item::value).containsExactly("@method", "@target-uri");
        assertThat(first.parameters()).containsExactly(
                java.util.Map.entry("created", 1618884475L), java.util.Map.entry("keyid", "test-key"));
        var second = (InnerList) dictionary.get("sig2");
        assertThat(second.items()).isEmpty();
        assertThat(second.parameters().get("alg")).isEqualTo(new Token("tok"));
    }

    @Test
    void readsByteSequencesBooleansAndBareKeys() {
        var dictionary = StructuredFields.parseDictionary("a=:aGVsbG8=:, b=?0, c, d=-42;x");

        assertThat((byte[]) ((Item) dictionary.get("a")).value()).isEqualTo("hello".getBytes(StandardCharsets.US_ASCII));
        assertThat(((Item) dictionary.get("b")).value()).isEqualTo(false);
        assertThat(((Item) dictionary.get("c")).value()).isEqualTo(true);
        var d = (Item) dictionary.get("d");
        assertThat(d.value()).isEqualTo(-42L);
        assertThat(d.parameters().get("x")).isEqualTo(true);
    }

    @Test
    void readsEscapesInStrings() {
        var item = (Item) StructuredFields.parseDictionary("a=\"say \\\"hi\\\" \\\\ there\"").get("a");

        assertThat(item.value()).isEqualTo("say \"hi\" \\ there");
    }

    @Test
    void allowsSpacesAndTabsAroundCommasAndSpacesInsideInnerLists() {
        var dictionary = StructuredFields.parseDictionary("  a=(  1   2 ) ,\tb=3  ");

        assertThat(((InnerList) dictionary.get("a")).items()).extracting(Item::value).containsExactly(1L, 2L);
        assertThat(((Item) dictionary.get("b")).value()).isEqualTo(3L);
    }

    @Test
    void readsAnEmptyFieldAsAnEmptyDictionary() {
        assertThat(StructuredFields.parseDictionary("")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a=1,",
            "a=1, ",
            "a=1,,b=2",
            "A=1",
            "a=1, a=2",
            "a=();x=1;x=2",
            "a=1.5",
            "a=1234567890123456",
            "a=-",
            "a=@1659578233",
            "a=%\"text\"",
            "a=\"unterminated",
            "a=\"bad \\x escape\"",
            "a=\"tab\there\"",
            "a=\"ü\"",
            "a=:not base64!:",
            "a=:aGVsbG8=",
            "a=?2",
            "a=(1 2",
            "a=(1,2)",
            "a=1 b=2",
            "a=\tb",
    })
    void rejectsWhatTheRfcDoesNotAllow(String field) {
        assertThatThrownBy(() -> StructuredFields.parseDictionary(field))
                .isInstanceOfSatisfying(SignatureVerificationException.class,
                        e -> assertThat(e.failure()).isEqualTo(Failure.MALFORMED));
    }

    @Test
    void serializesInnerListsAndItemsInCanonicalForm() {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("created", 1618884475L);
        parameters.put("keyid", "a \"quoted\" \\ key");
        parameters.put("flag", true);
        parameters.put("off", false);
        parameters.put("tok", new Token("rsa"));
        var list = new InnerList(
                List.of(new Item("@method", new LinkedHashMap<>()), new Item("content-digest", new LinkedHashMap<>())),
                parameters);

        assertThat(StructuredFields.serialize(list)).isEqualTo(
                "(\"@method\" \"content-digest\");created=1618884475;keyid=\"a \\\"quoted\\\" \\\\ key\";flag;off=?0;tok=rsa");
        assertThat(StructuredFields.serialize(new Item(new byte[]{1, 2, 3}, new LinkedHashMap<>()))).isEqualTo(":AQID:");
    }

    @Test
    void serializingWhatWasReadGivesTheCanonicalForm() {
        var list = (InnerList) StructuredFields.parseDictionary(
                "sig1=(  \"@method\"    \"@path\" );created=1;keyid=\"k\"").get("sig1");

        assertThat(StructuredFields.serialize(list)).isEqualTo("(\"@method\" \"@path\");created=1;keyid=\"k\"");
    }
}
