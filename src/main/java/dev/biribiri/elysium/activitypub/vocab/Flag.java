package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.util.List;
import java.util.stream.Stream;

/**
 * A report forwarded to the server of the account it's about. It's sent as the instance actor, so that the server learns
 * what was reported but not who reported it, as Mastodon does.
 *
 * @param content the reporter's comment, as plain text
 * @param object  the reported account, followed by its reported statuses
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Flag(
        @JsonProperty("@context") String context,
        String id,
        String type,
        URI actor,
        String content,
        List<URI> object,
        List<URI> to
) {

    public static Flag of(String id, URI instanceActor, String comment, URI target, List<URI> statuses) {
        return new Flag(ActivityStreams.CONTEXT, id, "Flag", instanceActor, comment,
                Stream.concat(Stream.of(target), statuses.stream()).toList(), List.of(target));
    }
}
