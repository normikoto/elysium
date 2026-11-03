package dev.biribiri.elysium.activitypub.signature;

/**
 * Understands one signature format. {@link SignatureVerifier} uses the first scheme in order that applies to a request,
 * so formats that are preferred should have a lower {@link org.springframework.core.annotation.Order}.
 */
interface SignatureScheme {

    boolean appliesTo(HttpRequestData request);

    /**
     * Reads a request's signature and does every check that doesn't need a key, including that the signature covers
     * enough of the request and that any digest matches the body. This doesn't do any I/O, because a request that fails
     * here shouldn't be able to make this server fetch anything.
     *
     * @throws SignatureVerificationException if the signature can't be accepted
     */
    SignatureClaim parse(HttpRequestData request);
}
