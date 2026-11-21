package dev.biribiri.elysium.actor;

import java.util.UUID;

/**
 * Removes what one module stores by or about actors once it shouldn't be kept. Each module that stores data about actors
 * implements this once, next to its tables, so that a new table's cleanup is written where the table is. The cleanup jobs
 * call every implementation, in no particular order, so one can't rely on another's having run. The jobs also run twice, to
 * catch what was saved while the first run went on, so each method has to be safe to repeat.
 */
public interface ActorDataRemoval {

    /**
     * Removes everything stored by or about a deleted actor, local or remote, including files in the media directory. The
     * actor itself is kept, since other things can refer to it.
     */
    void removeForDeleted(UUID actorId);

    /**
     * Removes what a suspended remote actor loses. Most of what it has is kept, since the suspension can be lifted.
     */
    default void removeForSuspendedRemote(UUID actorId) {
    }

    /**
     * Removes what's stored by and about the remote actors on a server that's been suspended. A local account's own choices
     * about them, such as blocks and mutes, are kept, since the domain block can be lifted.
     *
     * @param domain a domain as {@link Actor#domain} has it, from {@link ActorService#remoteDomains}
     */
    void removeForDomain(String domain);
}
