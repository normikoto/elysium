package dev.biribiri.elysium.content;

/**
 * Implemented once by each module, other than this one, whose tables refer to statuses, so that {@link RemoteStatusExpiry}
 * keeps a status that something here still needs. Its rows go with the status through a cascade, so a module that doesn't
 * say so loses them, with nothing published to tell it.
 */
public interface StatusReferences {

    /**
     * An SQL condition that's true when this module's tables refer to the status, which is run for every status the job
     * looks at, so it should be served by an index on the status's ID.
     *
     * @param statusId an SQL expression for the status's ID, such as {@code s.id}
     */
    String refersTo(String statusId);
}
