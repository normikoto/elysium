package dev.biribiri.elysium.activitypub.signature;

import java.time.Instant;

/**
 * What a signature claims, in a form that doesn't depend on the signature format. Nothing here has been checked against
 * a key yet.
 *
 * @param signatureBase the bytes that were signed
 * @param created       when the request was signed. This has to be covered by the signature, because otherwise old
 *                      requests could be sent again with a new time.
 * @param expires       when the signature ends, or null if it doesn't say. This has to be covered by the signature too.
 */
record SignatureClaim(
        String keyId,
        SignatureAlgorithm algorithm,
        byte[] signatureBase,
        byte[] signature,
        Instant created,
        Instant expires
) {
}
