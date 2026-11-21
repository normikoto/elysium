/**
 * Integrity proofs on activities (FEP-8b32), with the {@code eddsa-jcs-2022} cryptosuite.
 *
 * <p>Proofs are added where an activity's body is stored for delivery, so that every activity gets one, and the body sent
 * is the one that was signed. On activities that arrive, they're only checked on forwarded ones, which would otherwise be
 * fetched from their origin. One that's delivered by its actor already has that actor's HTTP signature.
 *
 * <p>{@link dev.biribiri.elysium.activitypub.proof.Jcs} has to produce the same bytes as every other implementation of
 * RFC 8785, or no other server can verify these proofs, so its tests compare it with JavaScript's output, and the proofs
 * with W3C's test vectors.
 */
package dev.biribiri.elysium.activitypub.proof;
