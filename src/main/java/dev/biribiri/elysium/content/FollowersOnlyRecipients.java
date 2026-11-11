package dev.biribiri.elysium.content;

import dev.biribiri.elysium.content.internal.FollowersOnlyRecipientRepository;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Which remote followers each local followers-only status or boost was sent to. Follows can't say this once someone has
 * unfollowed, or followed again since, which makes their follow look newer than the status. Without this, those servers
 * would never be sent its {@code Delete} or {@code Undo}, and would keep a private status that's gone. It's kept here,
 * rather than with federation, because it also decides who can fetch a status. A boost is recorded under its own status
 * ID, and its booster as the author.
 */
@Service
public class FollowersOnlyRecipients {

    private final FollowersOnlyRecipientRepository recipients;

    FollowersOnlyRecipients(FollowersOnlyRecipientRepository recipients) {
        this.recipients = recipients;
    }

    /**
     * Adds to who a status was sent to. This can run again for the same status.
     */
    public void record(UUID statusId, UUID authorId, Collection<UUID> actorIds) {
        recipients.record(statusId, authorId, actorIds);
    }

    /**
     * The inboxes of the active actors that a status was sent to, together with {@code others}, with one inbox for those
     * that share one.
     *
     * @param others    actors to include whether or not they're recorded
     * @param onlyThese if not null, leaves out every actor not among these
     */
    public List<URI> inboxes(UUID statusId, Collection<UUID> others, Collection<UUID> onlyThese) {
        return recipients.inboxes(statusId, others, onlyThese);
    }

    public boolean wasSent(UUID statusId, UUID actorId) {
        return recipients.wasSent(statusId, actorId);
    }

    /**
     * For once a status's {@code Delete}, or a boost's {@code Undo}, has been queued, when nothing more is sent for it.
     */
    public void forget(UUID statusId) {
        recipients.forget(statusId);
    }

    /**
     * Removes the statuses an actor wrote or boosted, and what it was sent.
     */
    public void removeForActor(UUID actorId) {
        recipients.removeForActor(actorId);
    }

    /**
     * Removes what a domain's actors were sent.
     *
     * @param domain a domain as {@code actor.domain} has it
     */
    public void removeForDomain(String domain) {
        recipients.removeForDomain(domain);
    }
}
