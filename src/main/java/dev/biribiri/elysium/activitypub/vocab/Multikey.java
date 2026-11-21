package dev.biribiri.elysium.activitypub.vocab;

import java.net.URI;
import java.util.Arrays;
import java.util.Base64;

/**
 * A public key in an actor's {@code assertionMethod}, as FEP-521a describes, which checks the actor's integrity proofs.
 *
 * @param controller         the actor, which FEP-521a requires
 * @param publicKeyMultibase {@code z}, for base58btc, then the key's multicodec prefix and its raw bytes
 */
public record Multikey(String id, String type, URI controller, String publicKeyMultibase) {

    /**
     * The DER that comes before the 32 bytes of every Ed25519 key in SubjectPublicKeyInfo form (RFC 8410).
     */
    private static final byte[] ED25519_SPKI_PREFIX = {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };
    private static final int ED25519_KEY_BYTES = 32;

    /**
     * The varint of the {@code ed25519-pub} multicodec, 0xed.
     */
    private static final byte[] ED25519_MULTICODEC = {(byte) 0xed, 0x01};

    /**
     * @param publicKeyPem an Ed25519 key in SubjectPublicKeyInfo form, as {@code actor} stores it
     * @throws IllegalArgumentException if it isn't one
     */
    public static Multikey ed25519(String id, URI controller, String publicKeyPem) {
        var der = Base64.getMimeDecoder().decode(publicKeyPem.replaceAll("-----[A-Z ]+-----", ""));
        if (der.length != ED25519_SPKI_PREFIX.length + ED25519_KEY_BYTES
                || !Arrays.equals(der, 0, ED25519_SPKI_PREFIX.length, ED25519_SPKI_PREFIX, 0, ED25519_SPKI_PREFIX.length)) {
            throw new IllegalArgumentException("Not an Ed25519 public key");
        }
        var prefixed = new byte[ED25519_MULTICODEC.length + ED25519_KEY_BYTES];
        System.arraycopy(ED25519_MULTICODEC, 0, prefixed, 0, ED25519_MULTICODEC.length);
        System.arraycopy(der, ED25519_SPKI_PREFIX.length, prefixed, ED25519_MULTICODEC.length, ED25519_KEY_BYTES);
        return new Multikey(id, "Multikey", controller, "z" + Base58.encode(prefixed));
    }
}
