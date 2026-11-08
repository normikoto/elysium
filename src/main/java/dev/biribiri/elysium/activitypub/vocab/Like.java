package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.util.List;

/** A like addressed only to the post's author, including for a private or direct post. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Like(@JsonProperty("@context") String context, String id, String type,
                   URI actor, URI object, List<URI> to) {
    public static Like of(String id, URI actor, URI status, URI author) {
        return new Like(ActivityStreams.CONTEXT, id, "Like", actor, status, List.of(author));
    }

    Like withoutContext() {
        return new Like(null, id, type, actor, object, to);
    }
}
