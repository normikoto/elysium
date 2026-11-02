package dev.biribiri.elysium.activitypub.vocab;

import java.net.URI;

public record PublicKey(String id, URI owner, String publicKeyPem) {
}
