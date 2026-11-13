package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * A {@code Create} of a vote in a poll on another server, as Mastodon sends one: a {@code Note} named after the option
 * chosen, in reply to the poll, addressed only to its author. Neither can be fetched, as on Mastodon.
 */
public record Vote(
        @JsonProperty("@context") Object context,
        URI id,
        String type,
        URI actor,
        List<String> to,
        Choice object
) {

    public record Choice(URI id, String type, URI attributedTo, List<String> to, String name, URI inReplyTo) {
    }

    /**
     * @param voteId which vote this is, which makes its ID
     * @param name   the option chosen
     */
    public static Vote of(URI voter, UUID voteId, String name, URI question, URI questionAuthor) {
        var id = URI.create(voter + "#votes/" + voteId);
        var to = List.of(questionAuthor.toString());
        return new Vote(
                ActivityStreams.CONTEXT,
                URI.create(id + "/activity"),
                "Create",
                voter,
                to,
                new Choice(id, "Note", voter, to, name, question));
    }
}
