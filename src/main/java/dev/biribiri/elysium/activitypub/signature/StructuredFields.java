package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException.Failure;

import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * Reads and writes the parts of RFC 9651 structured fields that RFC 9421 signatures and RFC 9530 digests use:
 * dictionaries of items and inner lists, with integers, strings, tokens, byte sequences, and booleans.
 *
 * <p>Parsing follows the RFC's algorithm, which rejects anything it doesn't describe, with two changes that make it
 * stricter. Decimals, dates, and display strings are rejected, because no field read here can have them. A key that's
 * repeated is rejected rather than replacing the earlier one, so that two parts of this server can't read different
 * values from the same field.
 */
final class StructuredFields {

    /**
     * A token, kept apart from a string because the two are serialized differently.
     */
    record Token(String value) {
    }

    /**
     * @param value a {@link Long}, {@link String}, {@link Token}, {@code byte[]}, or {@link Boolean}
     */
    record Item(Object value, SequencedMap<String, Object> parameters) {
    }

    record InnerList(List<Item> items, SequencedMap<String, Object> parameters) {
    }

    private static final int MAX_INTEGER_DIGITS = 15;

    private static final BitSet TOKEN_CHARACTERS = new BitSet();
    private static final BitSet KEY_CHARACTERS = new BitSet();
    private static final BitSet BASE64_CHARACTERS = new BitSet();

    static {
        "!#$%&'*+-.^_`|~:/0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                .chars()
                .forEach(TOKEN_CHARACTERS::set);
        "_-.*0123456789abcdefghijklmnopqrstuvwxyz".chars().forEach(KEY_CHARACTERS::set);
        "+/=0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".chars().forEach(BASE64_CHARACTERS::set);
    }

    private final String text;
    private int position;

    private StructuredFields(String text) {
        this.text = text;
    }

    /**
     * @param fieldValue the field's lines, joined with commas as RFC 9651 says
     * @return each member, which is an {@link Item} or an {@link InnerList}, in the order they were sent
     * @throws SignatureVerificationException if the field isn't a valid dictionary
     */
    static SequencedMap<String, Object> parseDictionary(String fieldValue) {
        var parser = new StructuredFields(fieldValue);
        parser.skipSpaces();
        var dictionary = parser.dictionary();
        parser.skipSpaces();
        if (!parser.atEnd()) {
            throw malformed("Field has unexpected text at position " + parser.position);
        }
        return dictionary;
    }

    static String serialize(InnerList list) {
        var text = new StringBuilder("(");
        for (var i = 0; i < list.items().size(); i++) {
            if (i > 0) {
                text.append(' ');
            }
            serializeItem(list.items().get(i), text);
        }
        text.append(')');
        serializeParameters(list.parameters(), text);
        return text.toString();
    }

    static String serialize(Item item) {
        var text = new StringBuilder();
        serializeItem(item, text);
        return text.toString();
    }

    private SequencedMap<String, Object> dictionary() {
        var members = new LinkedHashMap<String, Object>();
        while (!atEnd()) {
            var key = key();
            Object member;
            if (peek('=')) {
                position++;
                member = peek('(') ? innerList() : item();
            } else {
                member = new Item(Boolean.TRUE, parameters());
            }
            if (members.putIfAbsent(key, member) != null) {
                throw malformed("Field has more than one " + key);
            }
            skipOptionalWhitespace();
            if (atEnd()) {
                break;
            }
            expect(',');
            skipOptionalWhitespace();
            if (atEnd()) {
                throw malformed("Field ends with a comma");
            }
        }
        return members;
    }

    private InnerList innerList() {
        expect('(');
        var items = new ArrayList<Item>();
        while (true) {
            skipSpaces();
            if (peek(')')) {
                position++;
                return new InnerList(List.copyOf(items), parameters());
            }
            items.add(item());
            if (!peek(' ') && !peek(')')) {
                throw malformed("Inner list is missing a space or ')' at position " + position);
            }
        }
    }

    private Item item() {
        return new Item(bareItem(), parameters());
    }

    private SequencedMap<String, Object> parameters() {
        var parameters = new LinkedHashMap<String, Object>();
        while (peek(';')) {
            position++;
            skipSpaces();
            var key = key();
            Object value = Boolean.TRUE;
            if (peek('=')) {
                position++;
                value = bareItem();
            }
            if (parameters.putIfAbsent(key, value) != null) {
                throw malformed("Field has more than one parameter " + key);
            }
        }
        return parameters;
    }

    private String key() {
        if (atEnd() || !(isLowercaseLetter(current()) || current() == '*')) {
            throw malformed("Field is missing a key at position " + position);
        }
        var start = position;
        while (!atEnd() && KEY_CHARACTERS.get(current())) {
            position++;
        }
        return text.substring(start, position);
    }

    private Object bareItem() {
        if (atEnd()) {
            throw malformed("Field is missing a value at the end");
        }
        var first = current();
        if (first == '-' || isDigit(first)) {
            return integer();
        }
        if (first == '"') {
            return string();
        }
        if (first == ':') {
            return byteSequence();
        }
        if (first == '?') {
            return bool();
        }
        if (first == '*' || isLetter(first)) {
            return token();
        }
        throw malformed("Field has an unsupported value at position " + position);
    }

    private Long integer() {
        var start = position;
        if (peek('-')) {
            position++;
        }
        var digitsStart = position;
        while (!atEnd() && isDigit(current())) {
            position++;
        }
        var digits = position - digitsStart;
        if (digits == 0 || digits > MAX_INTEGER_DIGITS || peek('.')) {
            throw malformed("Field has an invalid integer at position " + start);
        }
        return Long.parseLong(text.substring(start, position));
    }

    private String string() {
        var value = new StringBuilder();
        position++;
        while (!atEnd()) {
            var character = text.charAt(position++);
            if (character == '"') {
                return value.toString();
            }
            if (character == '\\') {
                if (atEnd() || !(current() == '"' || current() == '\\')) {
                    throw malformed("Field has an invalid escape in a string");
                }
                character = text.charAt(position++);
            } else if (character < 0x20 || character > 0x7e) {
                throw malformed("Field has a character that isn't allowed in a string");
            }
            value.append(character);
        }
        throw malformed("Field has an unterminated string");
    }

    private Token token() {
        var start = position;
        position++;
        while (!atEnd() && TOKEN_CHARACTERS.get(current())) {
            position++;
        }
        return new Token(text.substring(start, position));
    }

    private byte[] byteSequence() {
        position++;
        var start = position;
        while (!atEnd() && BASE64_CHARACTERS.get(current())) {
            position++;
        }
        var encoded = text.substring(start, position);
        expect(':');
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw malformed("Field has a byte sequence that isn't valid base64");
        }
    }

    private Boolean bool() {
        position++;
        if (peek('1') || peek('0')) {
            return text.charAt(position++) == '1';
        }
        throw malformed("Field has an invalid boolean at position " + position);
    }

    private static void serializeItem(Item item, StringBuilder text) {
        serializeBareItem(item.value(), text);
        serializeParameters(item.parameters(), text);
    }

    private static void serializeParameters(Map<String, Object> parameters, StringBuilder text) {
        parameters.forEach((key, value) -> {
            text.append(';').append(key);
            if (!Boolean.TRUE.equals(value)) {
                text.append('=');
                serializeBareItem(value, text);
            }
        });
    }

    private static void serializeBareItem(Object value, StringBuilder text) {
        switch (value) {
            case Long integer -> text.append(integer);
            case String string -> {
                text.append('"');
                for (var character : string.toCharArray()) {
                    if (character == '"' || character == '\\') {
                        text.append('\\');
                    }
                    text.append(character);
                }
                text.append('"');
            }
            case Token token -> text.append(token.value());
            case byte[] bytes -> text.append(':').append(Base64.getEncoder().encodeToString(bytes)).append(':');
            case Boolean bool -> text.append(bool ? "?1" : "?0");
            default -> throw new IllegalArgumentException("Can't serialize " + value.getClass());
        }
    }

    private void expect(char expected) {
        if (!peek(expected)) {
            throw malformed("Field is missing '" + expected + "' at position " + position);
        }
        position++;
    }

    private void skipSpaces() {
        while (peek(' ')) {
            position++;
        }
    }

    private void skipOptionalWhitespace() {
        while (peek(' ') || peek('\t')) {
            position++;
        }
    }

    private boolean peek(char expected) {
        return !atEnd() && text.charAt(position) == expected;
    }

    private char current() {
        return text.charAt(position);
    }

    private boolean atEnd() {
        return position >= text.length();
    }

    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    private static boolean isLowercaseLetter(char character) {
        return character >= 'a' && character <= 'z';
    }

    private static boolean isLetter(char character) {
        return isLowercaseLetter(character) || (character >= 'A' && character <= 'Z');
    }

    private static SignatureVerificationException malformed(String message) {
        return new SignatureVerificationException(Failure.MALFORMED, message);
    }
}
