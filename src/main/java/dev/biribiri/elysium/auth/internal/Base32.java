package dev.biribiri.elysium.auth.internal;

/**
 * RFC 4648 Base32, which is how authenticator apps expect TOTP secrets and how recovery codes are written. Only encoding is
 * needed, since nobody types a secret back in, and recovery codes are compared by their hashes.
 */
final class Base32 {

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private Base32() {
    }

    /**
     * @return the encoding without {@code =} padding, which some authenticator apps refuse in a secret
     */
    static String encode(byte[] bytes) {
        var encoded = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                encoded.append(ALPHABET[(buffer >>> (bits - 5)) & 0x1f]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            encoded.append(ALPHABET[(buffer << (5 - bits)) & 0x1f]);
        }
        return encoded.toString();
    }
}
