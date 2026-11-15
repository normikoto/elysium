package dev.biribiri.elysium.graph;

/**
 * SQL conditions on lists' members, for other modules' queries that have to check them for many statuses or lists at
 * once, as {@link Following} does for follows.
 */
public final class ListMembers {

    private ListMembers() {
    }

    /**
     * @param list  the list's ID, as SQL
     * @param actor the actor's ID, as SQL, which can be null for no one
     */
    public static String inList(String list, String actor) {
        return "EXISTS (SELECT 1 FROM account_list_member lm WHERE lm.list_id = " + list + " AND lm.target_id = " + actor
                + ")";
    }

    /**
     * Whether the actor is in one of the owner's exclusive lists, which keeps their statuses out of the owner's home
     * timeline.
     *
     * @param owner the owner's ID, as SQL
     * @param actor the actor's ID, as SQL
     */
    public static String inExclusiveList(String owner, String actor) {
        return "EXISTS (SELECT 1 FROM account_list_member lm JOIN account_list l ON l.id = lm.list_id"
                + " WHERE lm.account_id = " + owner + " AND lm.target_id = " + actor + " AND l.exclusive)";
    }
}
