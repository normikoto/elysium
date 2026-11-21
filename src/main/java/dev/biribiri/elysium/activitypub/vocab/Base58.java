package dev.biribiri.elysium.activitypub.vocab;

import java.math.BigInteger;

/**
 * The base58btc encoding that FEP-521a requires for {@code publicKeyMultibase}, and {@code eddsa-jcs-2022} for a proof's
 * {@code proofValue}. It's only used for public keys and signatures, so it doesn't need to take the same time for every
 * input, as an encoder of secrets would.
 */
public final class Base58 {

    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(58);

    private Base58() {
    }

    public static String encode(byte[] bytes) {
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

    /**
     * @param maxLength the longest text to accept. Other servers send the text, and decoding takes time that grows with the
     *                  square of its length.
     * @throws IllegalArgumentException if it isn't base58btc, or it's too long
     */
    public static byte[] decode(String text, int maxLength) {
        if (text.length() > maxLength) {
            throw new IllegalArgumentException("Too long for base58btc here");
        }
        var value = BigInteger.ZERO;
        for (var i = 0; i < text.length(); i++) {
            var digit = ALPHABET.indexOf(text.charAt(i));
            if (digit == -1) {
                throw new IllegalArgumentException("Not base58btc");
            }
            value = value.multiply(BASE).add(BigInteger.valueOf(digit));
        }
        var zeros = 0;
        while (zeros < text.length() && text.charAt(zeros) == ALPHABET.charAt(0)) {
            zeros++;
        }
        var magnitude = value.signum() == 0 ? new byte[0] : value.toByteArray();
        // BigInteger adds a zero byte in front when the top bit is set, so that the number stays positive.
        var offset = magnitude.length > 1 && magnitude[0] == 0 ? 1 : 0;
        var bytes = new byte[zeros + magnitude.length - offset];
        System.arraycopy(magnitude, offset, bytes, zeros, magnitude.length - offset);
        return bytes;
    }
}
