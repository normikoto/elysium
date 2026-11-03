package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException.Failure;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the parameters of a draft-cavage {@code Signature} header. Values are kept as the text that was sent, because
 * {@code (created)} and {@code (expires)} go into the signature base that way.
 */
final class CavageSignatureHeader {

    private static final BitSet TOKEN_CHARACTERS = new BitSet();

    static {
        "!#$%&'*+.^_`|~-0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                .chars()
                .forEach(TOKEN_CHARACTERS::set);
    }

    /**
     * Old versions of node-http-signature send this, because the header's value started out as an
     * {@code Authorization} scheme.
     */
    private static final String LEGACY_PREFIX = "Signature ";

    private final String text;
    private int position;

    private CavageSignatureHeader(String text) {
        this.text = text;
    }

    /**
     * Unknown parameters are kept, and it's up to the caller to ignore them.
     *
     * @throws SignatureVerificationException if the header is malformed, or has a parameter twice
     */
    static Map<String, String> parse(String header) {
        var stripped = header.strip();
        var text = stripped.startsWith(LEGACY_PREFIX) ? stripped.substring(LEGACY_PREFIX.length()) : stripped;
        return new CavageSignatureHeader(text).parameters();
    }

    private Map<String, String> parameters() {
        var parameters = new LinkedHashMap<String, String>();
        while (true) {
            var name = token();
            skipWhitespace();
            expect('=');
            skipWhitespace();
            var value = !atEnd() && text.charAt(position) == '"' ? quotedString() : token();
            if (parameters.putIfAbsent(name, value) != null) {
                throw malformed("Signature header has more than one " + name);
            }
            skipWhitespace();
            if (atEnd()) {
                return parameters;
            }
            expect(',');
            skipWhitespace();
        }
    }

    private String token() {
        var start = position;
        while (!atEnd() && TOKEN_CHARACTERS.get(text.charAt(position))) {
            position++;
        }
        if (position == start) {
            throw malformed("Signature header has a missing name or value at position " + start);
        }
        return text.substring(start, position);
    }

    private String quotedString() {
        var value = new StringBuilder();
        position++;
        while (!atEnd()) {
            var character = text.charAt(position++);
            if (character == '"') {
                return value.toString();
            }
            if (character == '\\') {
                if (atEnd()) {
                    break;
                }
                character = text.charAt(position++);
            }
            value.append(character);
        }
        throw malformed("Signature header has an unterminated string");
    }

    private void expect(char expected) {
        if (atEnd() || text.charAt(position) != expected) {
            throw malformed("Signature header is missing '" + expected + "' at position " + position);
        }
        position++;
    }

    private void skipWhitespace() {
        while (!atEnd() && (text.charAt(position) == ' ' || text.charAt(position) == '\t')) {
            position++;
        }
    }

    private boolean atEnd() {
        return position >= text.length();
    }

    private static SignatureVerificationException malformed(String message) {
        return new SignatureVerificationException(Failure.MALFORMED, message);
    }
}
