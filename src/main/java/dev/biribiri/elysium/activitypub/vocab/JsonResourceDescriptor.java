package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.net.URI;
import java.util.List;

/**
 * A JSON Resource Descriptor (RFC 7033), such as a WebFinger response.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonResourceDescriptor(String subject, List<URI> aliases, List<Link> links) {

    /**
     * A descriptor that isn't about any particular subject, and only lists links.
     */
    public static JsonResourceDescriptor ofLinks(List<Link> links) {
        return new JsonResourceDescriptor(null, null, links);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Link(String rel, String type, URI href) {

        public Link(String rel, URI href) {
            this(rel, null, href);
        }
    }
}
