package dev.biribiri.elysium.graph.internal;

/**
 * SQL shared by the graph's repositories and {@link dev.biribiri.elysium.graph.HiddenAccounts}, so that a server block
 * matches the same way everywhere.
 */
public final class GraphSql {

    private GraphSql() {
    }

    /**
     * Whether an account has blocked a remote actor's server, or one it's a subdomain of. Actor domains can include a port,
     * and a block applies to the DNS name, as instance domain blocks do. Local actors are never matched.
     *
     * @param account the blocking account's ID, as SQL
     * @param actor   the alias of a joined {@code actor}
     */
    public static String domainBlocked(String account, String actor) {
        var host = "regexp_replace(" + actor + ".domain, ':[0-9]+$', '')";
        return "(NOT " + actor + ".is_local AND EXISTS (SELECT 1 FROM account_domain_block hd WHERE hd.account_id = "
                + account + " AND (" + host + " = hd.domain OR right(" + host + ", length(hd.domain) + 1) = '.' || hd.domain)))";
    }
}
