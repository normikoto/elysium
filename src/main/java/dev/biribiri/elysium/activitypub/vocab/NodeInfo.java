package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * A NodeInfo document, following version 2.0 or 2.1 of the schema.
 */
public record NodeInfo(
        String version,
        Software software,
        List<String> protocols,
        Services services,
        boolean openRegistrations,
        Usage usage,
        Map<String, Object> metadata) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Software(String name, String version, URI repository) {
    }

    public record Services(List<String> inbound, List<String> outbound) {
    }

    public record Usage(Users users, long localPosts) {
    }

    public record Users(long total) {
    }
}
