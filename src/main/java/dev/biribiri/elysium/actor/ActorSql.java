package dev.biribiri.elysium.actor;

/**
 * SQL conditions on a joined {@code actor}, for other modules' queries, so that they all agree on which actors are active
 * and which servers are blocked. Each takes the actor's alias.
 *
 * <p>The conditions about blocked servers read {@code domain_block}, which {@code moderation} owns and decides. They're
 * here because modules that {@code moderation} depends on, such as {@code content}, need them too, and this is the one
 * copy that SQL outside {@code moderation} uses. A block either suspends a server or only limits it, so each one says
 * which blocks count.
 */
public final class ActorSql {

    private ActorSql() {
    }

    /**
     * Whether the actor is neither suspended nor deleted.
     */
    public static String active(String actor) {
        return actor + ".suspended_at IS NULL AND " + actor + ".deleted_at IS NULL";
    }

    /**
     * Whether the actor is local, or its server isn't suspended. A block that only limits a server, such as by rejecting its
     * media, doesn't count.
     */
    public static String serverNotSuspended(String actor) {
        return "(" + actor + ".is_local OR NOT EXISTS (SELECT 1 FROM domain_block sb WHERE sb.severity = 'suspend' AND "
                + domainCovers("sb.domain", actor + ".domain") + "))";
    }

    /**
     * Whether a block that rejects media, or suspends, covers the domain.
     *
     * @param domainColumn a column holding a domain as actors have it, which can have a port
     */
    public static String mediaRejected(String domainColumn) {
        return "EXISTS (SELECT 1 FROM domain_block mb WHERE (mb.severity = 'suspend' OR mb.reject_media) AND "
                + domainCovers("mb.domain", domainColumn) + ")";
    }

    /**
     * Whether a domain as blocks have it covers a domain as actors and emoji have it: its subdomains too, and whatever the
     * port. Instance domain blocks and an account's own server blocks both match this way.
     *
     * @param blockDomain  a column or parameter holding a domain as blocks have it
     * @param domainColumn a column holding a domain as actors have it, which can have a port
     */
    public static String domainCovers(String blockDomain, String domainColumn) {
        var host = "regexp_replace(" + domainColumn + ", ':[0-9]+$', '')";
        return "(" + host + " = " + blockDomain + " OR right(" + host + ", length(" + blockDomain + ") + 1) = '.' || "
                + blockDomain + ")";
    }
}
