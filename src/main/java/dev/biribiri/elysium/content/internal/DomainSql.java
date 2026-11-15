package dev.biribiri.elysium.content.internal;

/**
 * How a domain as domain blocks have it covers an actor's or emoji's domain: its subdomains too, and whatever the port.
 * Every query in this module that applies a block builds the condition here, so that they can't disagree.
 */
final class DomainSql {

    private DomainSql() {
    }

    /**
     * @param domainColumn a column holding a domain as actors have it, which can have a port
     * @param blockDomain  a column or parameter holding a domain as blocks have it
     */
    static String covers(String blockDomain, String domainColumn) {
        var host = "regexp_replace(" + domainColumn + ", ':[0-9]+$', '')";
        return "(" + host + " = " + blockDomain + " OR right(" + host + ", length(" + blockDomain + ") + 1) = '.' || "
                + blockDomain + ")";
    }

    /**
     * Whether a block that rejects media, or suspends, still covers the domain.
     */
    static String mediaRejected(String domainColumn) {
        return "EXISTS (SELECT 1 FROM domain_block b WHERE (b.severity = 'suspend' OR b.reject_media) AND "
                + covers("b.domain", domainColumn) + ")";
    }
}
