package dev.biribiri.elysium.graph;

import dev.biribiri.elysium.actor.IdPage;
import dev.biribiri.elysium.graph.internal.TagFollowRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Local accounts' follows of hashtags, by their normalized names, which add public statuses with them to the home
 * timeline. Nothing about them is federated, as on Mastodon, so they only show what reaches this server.
 */
@Service
public class TagFollows {

    private final TagFollowRepository follows;

    TagFollows(TagFollowRepository follows) {
        this.follows = follows;
    }

    /**
     * That the viewer follows the tag, as a condition for other modules' SQL, so that the home timeline doesn't read
     * this module's table itself.
     *
     * @param viewer the viewer's ID, as SQL
     * @param name   the normalized tag name, as SQL
     */
    public static String follows(String viewer, String name) {
        return "EXISTS (SELECT 1 FROM tag_follow tf WHERE tf.account_id = " + viewer + " AND tf.name = " + name + ")";
    }

    /**
     * Following a tag already followed changes nothing, and keeps its place in {@link #followed(UUID, IdPage)}.
     *
     * @param name a normalized name
     */
    public void follow(UUID accountId, String name) {
        follows.save(accountId, name);
    }

    public void unfollow(UUID accountId, String name) {
        follows.delete(accountId, name);
    }

    /**
     * @return which of these normalized names the account follows
     */
    public Set<String> followed(UUID accountId, Collection<String> names) {
        return follows.find(accountId, names);
    }

    /**
     * Lists the tags an account follows, most recently followed first. The cursors are the follows' IDs.
     */
    public List<FollowedTag> followed(UUID accountId, IdPage page) {
        return follows.findPage(accountId, page);
    }

    void removeForAccount(UUID accountId) {
        follows.deleteForAccount(accountId);
    }
}
