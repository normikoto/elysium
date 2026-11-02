package dev.biribiri.elysium.activitypub.vocab;

import java.net.URI;
import java.util.List;

/**
 * A WebFinger response (RFC 7033).
 */
public record JsonResourceDescriptor(String subject, List<URI> aliases, List<Link> links) {

    public record Link(String rel, String type, URI href) {
    }
}
