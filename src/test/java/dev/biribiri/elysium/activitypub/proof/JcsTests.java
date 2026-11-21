package dev.biribiri.elysium.activitypub.proof;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The expected outputs are what JavaScript's {@code JSON.stringify} writes, which is how RFC 8785 defines them. Strings with
 * escapes are in files, since Java would read a backslash and a {@code u} in its source as an escape of its own.
 */
class JcsTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * RFC 8785's example in section 3.2.2.
     */
    @Test
    void canonicalizesTheRfcsExample() throws IOException {
        var input = JSON.readTree(resource("rfc8785-input.json"));

        assertThat(Jcs.canonicalize(input)).isEqualTo(resource("rfc8785-canonical.json"));
    }

    /**
     * RFC 8785's example in section 3.2.3, which sorts by UTF-16 code units, so the emoji comes before U+FB33.
     */
    @Test
    void sortsKeysByUtf16CodeUnits() {
        var keys = List.of(
                text(0x20ac), "\r", text(0xfb33), "1", text(0x1f600), text(0x80), text(0xf6));
        var object = JsonNodeFactory.instance.objectNode();
        for (var i = 0; i < keys.size(); i++) {
            object.put(keys.get(i), i);
        }

        var canonical = Jcs.canonicalize(object);

        var order = new ArrayList<Integer>();
        for (var value : JSON.readTree(canonical).properties()) {
            order.add(value.getValue().intValue());
        }
        assertThat(order).containsExactly(1, 3, 5, 6, 0, 4, 2);
    }

    @ParameterizedTest
    @CsvSource({
            "0000000000000000, 0",
            "8000000000000000, 0",
            "0000000000000001, 5e-324",
            "8000000000000001, -5e-324",
            "7fefffffffffffff, 1.7976931348623157e+308",
            "ffefffffffffffff, -1.7976931348623157e+308",
            "4340000000000000, 9007199254740992",
            "c340000000000000, -9007199254740992",
            "4430000000000000, 295147905179352830000",
            "44b52d02c7e14af5, 9.999999999999997e+22",
            "44b52d02c7e14af6, 1e+23",
            "44b52d02c7e14af7, 1.0000000000000001e+23",
            "444b1ae4d6e2ef4e, 999999999999999700000",
            "444b1ae4d6e2ef4f, 999999999999999900000",
            "444b1ae4d6e2ef50, 1e+21",
            "3eb0c6f7a0b5ed8c, 9.999999999999997e-7",
            "3eb0c6f7a0b5ed8d, 0.000001",
            "41b3de4355555553, 333333333.3333332",
            "41b3de4355555554, 333333333.33333325",
            "41b3de4355555555, 333333333.3333333",
            "41b3de4355555556, 333333333.3333334",
            "41b3de4355555557, 333333333.33333343",
            "becbf647612f3696, -0.0000033333333333333333",
            "43143ff3c1cb0959, 1424953923781206.2",
            "43143ff3c1cb095a, 1424953923781206.5",
            "43143ff3c1cb095b, 1424953923781206.8"
    })
    void writesNumbersAsJavaScriptDoes(String bits, String expected) {
        var value = Double.longBitsToDouble(Long.parseUnsignedLong(bits, 16));

        assertThat(Jcs.number(value)).isEqualTo(expected);
        assertThat(Jcs.canonicalize(JsonNodeFactory.instance.numberNode(value))).isEqualTo(expected);
    }

    @Test
    void writesIntegersAsTheyAre() {
        assertThat(Jcs.canonicalize(JSON.readTree("[0, -1, 42, 9007199254740992]"))).isEqualTo("[0,-1,42,9007199254740992]");
    }

    @Test
    void writesAnIntegerTooBigForADoubleAsTheDoubleItRoundsTo() {
        assertThat(Jcs.canonicalize(JSON.readTree("9007199254740993"))).isEqualTo("9007199254740992");
    }

    @Test
    void escapesOnlyWhatJavaScriptDoes() {
        var value = "a\"b\\c/d\b\f\n\r\t" + (char) 0x01 + (char) 0x1f + text(0xe9) + text(0x1f600);

        assertThat(Jcs.canonicalize(JsonNodeFactory.instance.stringNode(value)))
                .isEqualTo("\"a\\\"b\\\\c/d\\b\\f\\n\\r\\t" + backslashU("0001") + backslashU("001f")
                        + text(0xe9) + text(0x1f600) + "\"");
    }

    @Test
    void refusesHalfASurrogatePair() {
        var lone = String.valueOf((char) 0xd83d);

        assertThatThrownBy(() -> Jcs.canonicalize(JsonNodeFactory.instance.stringNode("a" + lone)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Jcs.canonicalize(JsonNodeFactory.instance.stringNode(lone + "a")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesNumbersThatAreNotFinite() {
        assertThatThrownBy(() -> Jcs.number(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Jcs.number(Double.POSITIVE_INFINITY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writesLiteralsAndNestingWithoutWhitespace() {
        assertThat(Jcs.canonicalize(JSON.readTree("{ \"b\" : [ true , null , { } , [ ] ], \"a\" : false }")))
                .isEqualTo("{\"a\":false,\"b\":[true,null,{},[]]}");
    }

    static String resource(String name) throws IOException {
        try (var in = JcsTests.class.getResourceAsStream("/proof/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String text(int codePoint) {
        return Character.toString(codePoint);
    }

    private static String backslashU(String hex) {
        return "\\" + "u" + hex;
    }
}
