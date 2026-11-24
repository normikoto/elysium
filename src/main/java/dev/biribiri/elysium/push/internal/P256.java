package dev.biribiri.elysium.push.internal;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;

/**
 * P-256 keys as Web Push passes them around: public keys as uncompressed points, which is what browsers give clients and
 * what clients are given.
 */
public final class P256 {

    public static final int PUBLIC_KEY_LENGTH = 65;

    private static final int COORDINATE_LENGTH = 32;
    private static final ECParameterSpec PARAMETERS;

    static {
        try {
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            PARAMETERS = ((ECPublicKey) generator.generateKeyPair().getPublic()).getParams();
        } catch (GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private P256() {
    }

    public static KeyPair generate() {
        try {
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(PARAMETERS);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encode(ECPublicKey key) {
        var encoded = new byte[PUBLIC_KEY_LENGTH];
        encoded[0] = 0x04;
        copyCoordinate(key.getW().getAffineX(), encoded, 1);
        copyCoordinate(key.getW().getAffineY(), encoded, 1 + COORDINATE_LENGTH);
        return encoded;
    }

    /**
     * @throws IllegalArgumentException if it isn't an uncompressed point on the curve. Checking that it's on the curve
     *                                  is what stops a client from learning about the private key it's combined with.
     */
    public static ECPublicKey decodePublic(byte[] encoded) {
        if (encoded.length != PUBLIC_KEY_LENGTH || encoded[0] != 0x04) {
            throw new IllegalArgumentException("Not an uncompressed P-256 point");
        }
        var x = new BigInteger(1, Arrays.copyOfRange(encoded, 1, 1 + COORDINATE_LENGTH));
        var y = new BigInteger(1, Arrays.copyOfRange(encoded, 1 + COORDINATE_LENGTH, PUBLIC_KEY_LENGTH));
        if (!isOnCurve(x, y)) {
            throw new IllegalArgumentException("Not a point on P-256");
        }
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), PARAMETERS));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not a P-256 public key", e);
        }
    }

    public static PrivateKey decodePrivate(byte[] pkcs8) {
        try {
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The stored private key can't be read", e);
        }
    }

    private static boolean isOnCurve(BigInteger x, BigInteger y) {
        var curve = PARAMETERS.getCurve();
        var p = ((ECFieldFp) curve.getField()).getP();
        if (x.signum() < 0 || x.compareTo(p) >= 0 || y.signum() < 0 || y.compareTo(p) >= 0) {
            return false;
        }
        var left = y.modPow(BigInteger.TWO, p);
        var right = x.pow(3).add(curve.getA().multiply(x)).add(curve.getB()).mod(p);
        return left.equals(right);
    }

    private static void copyCoordinate(BigInteger value, byte[] target, int offset) {
        var bytes = value.toByteArray();
        // toByteArray can add a sign byte or leave out leading zeros.
        var length = Math.min(bytes.length, COORDINATE_LENGTH);
        System.arraycopy(bytes, bytes.length - length, target, offset + COORDINATE_LENGTH - length, length);
    }
}
