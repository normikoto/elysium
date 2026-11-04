/**
 * Signing and verifying HTTP requests between servers.
 *
 * <p>{@link dev.biribiri.elysium.activitypub.signature.SignatureVerifier},
 * {@link dev.biribiri.elysium.activitypub.signature.PublicKeyResolver}, and
 * {@link dev.biribiri.elysium.activitypub.signature.SignedFetcher} don't depend on any particular signature format. Each
 * format supplies a {@link dev.biribiri.elysium.activitypub.signature.SignatureScheme} for verifying and a
 * {@link dev.biribiri.elysium.activitypub.signature.RequestSigner} for signing. Only draft-cavage-http-signatures-12 is
 * implemented so far. RFC 9421 can be added as another pair.
 *
 * <p>The two orders are separate choices. Schemes are chosen by what a request was signed with, so RFC 9421's can go
 * first. Signers are tried until a server stops refusing, so a signer should only go first if servers that don't
 * understand its format refuse it, because some are reported to answer with a 200 instead. That's why cavage should stay
 * first for now, which is also the order Mastodon sends in.
 */
package dev.biribiri.elysium.activitypub.signature;
