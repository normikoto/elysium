/**
 * Signing and verifying HTTP requests between servers.
 *
 * <p>{@link dev.biribiri.elysium.activitypub.signature.SignatureVerifier},
 * {@link dev.biribiri.elysium.activitypub.signature.PublicKeyResolver}, and
 * {@link dev.biribiri.elysium.activitypub.signature.SignedFetcher} don't depend on any particular signature format. Each
 * format supplies a {@link dev.biribiri.elysium.activitypub.signature.SignatureScheme} for verifying and a
 * {@link dev.biribiri.elysium.activitypub.signature.RequestSigner} for signing. draft-cavage-http-signatures-12 and
 * RFC 9421 are both implemented. Each scheme requires a signature to cover the same parts of a request, so that neither
 * is a weaker way in, and each signer signs what every scheme here requires. The rules they share are in
 * {@link dev.biribiri.elysium.activitypub.signature.SignedRequests}.
 *
 * <p>The two orders are separate choices. Schemes are chosen by what a request was signed with, so RFC 9421's goes
 * first. Signers are tried until a server stops refusing, so a signer should only go first if servers that don't
 * understand its format refuse it, because some are reported to answer with a 200 instead. That's why requests are signed
 * with cavage first, which is also the order Mastodon sends in.
 */
package dev.biribiri.elysium.activitypub.signature;
