package dev.biribiri.elysium.graph.internal;

import dev.biribiri.elysium.actor.ActorSql;

/**
 * SQL shared by the graph's repositories and {@link dev.biribiri.elysium.graph.HiddenAccounts}, so that a server block
 * matches the same way everywhere.
 */
public final class GraphSql {

    private GraphSql() {
    }

    /**
     * Whether an account has blocked a remote actor's server, or one it's a subdomain of, matched as instance domain blocks
     * are. Local actors are never matched.
     *
     * @param account the blocking account's ID, as SQL
     * @param actor   the alias of a joined {@code actor}
     */
    public static String domainBlocked(String account, String actor) {
        return "(NOT " + actor + ".is_local AND EXISTS (SELECT 1 FROM account_domain_block hd WHERE hd.account_id = "
                + account + " AND " + ActorSql.domainCovers("hd.domain", actor + ".domain") + "))";
    }
}
