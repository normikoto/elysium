package dev.biribiri.elysium.activitypub.proof;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;
import java.util.TreeMap;

/**
 * The JSON Canonicalization Scheme (RFC 8785), which {@code eddsa-jcs-2022} signs. Its output is what JavaScript's
 * {@code JSON.stringify} writes with each object's keys sorted, so another server can produce the same bytes from the
 * same JSON whatever its key order or whitespace.
 */
final class Jcs {

    /**
     * Integers up to this size are exactly the same as a double, so they can be written without converting them.
     */
    private static final BigInteger MAX_EXACT_INTEGER = BigInteger.TWO.pow(53);

    private Jcs() {
    }

    /**
     * @throws IllegalArgumentException if the JSON has a number that's not finite, or a string with half of a surrogate
     *                                  pair, neither of which RFC 8785 allows
     */
    static String canonicalize(JsonNode node) {
        var out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node.isObject()) {
            // Java compares strings by UTF-16 code units, which is the order RFC 8785 asks for.
            var sorted = new TreeMap<String, JsonNode>();
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                sorted.put(property.getKey(), property.getValue());
            }
            out.append('{');
            var first = true;
            for (var property : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(property.getKey(), out);
                out.append(':');
                write(property.getValue(), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (var i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(node.get(i), out);
            }
            out.append(']');
        } else if (node.isString()) {
            writeString(node.stringValue(), out);
        } else if (node.isNumber()) {
            out.append(number(node));
        } else if (node.isBoolean()) {
            out.append(node.booleanValue());
        } else if (node.isNull()) {
            out.append("null");
        } else {
            throw new IllegalArgumentException("Not JSON: " + node.getNodeType());
        }
    }

    private static String number(JsonNode node) {
        if (node.isIntegralNumber() && node.bigIntegerValue().abs().compareTo(MAX_EXACT_INTEGER) <= 0) {
            return node.bigIntegerValue().toString();
        }
        return number(node.doubleValue());
    }

    /**
     * What JavaScript's {@code Number.prototype.toString} writes, which RFC 8785 uses for every number. Since Java 19,
     * {@link Double#toString} picks the same shortest digits that it does, but lays them out differently.
     */
    static String number(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("RFC 8785 doesn't allow " + value);
        }
        if (value == 0) {
            // Negative zero too.
            return "0";
        }
        var decimal = shortest(Math.abs(value));
        var digits = decimal.unscaledValue().toString();
        var k = digits.length();
        // The decimal point goes after this many digits, as ECMA-262's Number::toString counts it.
        var n = k - decimal.scale();
        var text = new StringBuilder(value < 0 ? "-" : "");
        if (k <= n && n <= 21) {
            text.append(digits).append("0".repeat(n - k));
        } else if (0 < n && n <= 21) {
            text.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (-6 < n && n <= 0) {
            text.append("0.").append("0".repeat(-n)).append(digits);
        } else {
            text.append(digits.charAt(0));
            if (k > 1) {
                text.append('.').append(digits, 1, k);
            }
            var exponent = n - 1;
            text.append('e').append(exponent < 0 ? '-' : '+').append(Math.abs(exponent));
        }
        return text.toString();
    }

    /**
     * {@link Double#toString} always writes at least two digits, so where one would do, as for the smallest subnormal,
     * which JavaScript writes as {@code 5e-324}, this looks for that digit. It's the one of the two either side that reads
     * back as the same double, or the nearer one if both do.
     */
    private static BigDecimal shortest(double positive) {
        var decimal = new BigDecimal(Double.toString(positive)).stripTrailingZeros();
        if (decimal.precision() != 2) {
            return decimal;
        }
        var exact = new BigDecimal(positive);
        BigDecimal best = null;
        for (var mode : new RoundingMode[]{RoundingMode.FLOOR, RoundingMode.CEILING}) {
            var candidate = decimal.round(new MathContext(1, mode));
            if (candidate.doubleValue() == positive && (best == null
                    || candidate.subtract(exact).abs().compareTo(best.subtract(exact).abs()) < 0)) {
                best = candidate;
            }
        }
        return best == null ? decimal : best.stripTrailingZeros();
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else if (Character.isHighSurrogate(c)
                            && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                        out.append(c).append(value.charAt(++i));
                    } else if (Character.isSurrogate(c)) {
                        throw new IllegalArgumentException("Half of a surrogate pair at " + i);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
