package dev.biribiri.elysium.actor;

/**
 * An SQL condition for an account letting anyone's search find its public statuses, for other modules' queries that have
 * to check it for many accounts at once rather than read each one's profile first.
 */
public final class Indexable {

    private Indexable() {
    }

    /**
     * @param actorId the account's ID, as SQL
     */
    public static String account(String actorId) {
        return "EXISTS (SELECT 1 FROM profile ip WHERE ip.actor_id = " + actorId + " AND ip.indexable)";
    }
}
