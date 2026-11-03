package dev.biribiri.elysium.activitypub.vocab;

import java.net.URI;
import java.util.List;

/**
 * The document at {@code /.well-known/nodeinfo}, which links to the NodeInfo documents this server provides. This is a
 * JRD, like {@link JsonResourceDescriptor}, but one that isn't about any particular subject.
 */
public record NodeInfoLinks(List<Link> links) {

    public record Link(String rel, URI href) {
    }
}
