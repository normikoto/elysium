package dev.biribiri.elysium.activitypub.signature;

import java.net.URI;

/**
 * A signature that checked out.
 *
 * @param owner the actor that owns the signing key. This only says who signed the request, so callers still have to
 *              compare it to the actor in an activity.
 */
public record VerifiedSignature(String keyId, URI owner) {
}
