package dev.biribiri.elysium.activitypub.vocab;

import java.math.BigInteger;

/**
 * The base58btc encoding that FEP-521a requires for {@code publicKeyMultibase}. It's only used for public keys, so it
 * doesn't need to take the same time for every input, as an encoder of secrets would.
 */
final class Base58 {

    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(58);

    private Base58() {
    }

    static String encode(byte[] bytes) {
        var text = new StringBuilder();
        var value = new BigInteger(1, bytes);
        while (value.signum() > 0) {
            var division = value.divideAndRemainder(BASE);
            text.append(ALPHABET.charAt(division[1].intValue()));
            value = division[0];
        }
        // Each leading zero byte is written as a 1, since the number alone would lose them.
        for (var i = 0; i < bytes.length && bytes[i] == 0; i++) {
            text.append(ALPHABET.charAt(0));
        }
        return text.reverse().toString();
    }
}
