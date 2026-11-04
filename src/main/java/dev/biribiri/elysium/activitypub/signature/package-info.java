/**
 * Signing and verifying HTTP requests between servers.
 *
 * <p>{@link dev.biribiri.elysium.activitypub.signature.SignatureVerifier},
 * {@link dev.biribiri.elysium.activitypub.signature.PublicKeyResolver}, and
 * {@link dev.biribiri.elysium.activitypub.signature.SignedFetcher} don't depend on any particular signature format. Each
 * format supplies a {@link dev.biribiri.elysium.activitypub.signature.SignatureScheme} for verifying and a
 * {@link dev.biribiri.elysium.activitypub.signature.RequestSigner} for signing. Only draft-cavage-http-signatures-12 is
 * implemented so far. RFC 9421 can be added as another pair, with both ordered first.
 */
package dev.biribiri.elysium.activitypub.signature;
