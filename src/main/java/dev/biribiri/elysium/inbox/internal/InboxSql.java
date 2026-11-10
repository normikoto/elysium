package dev.biribiri.elysium.inbox.internal;

import dev.biribiri.elysium.inbox.InboxPage;

import java.util.Map;

/** Apply eligibility before paging or counting; future per-account blocks and mutes belong in these predicates too. */
final class InboxSql {
    private InboxSql() {
    }

    static String active(String alias) {
        return alias + ".suspended_at IS NULL AND " + notGone(alias);
    }

    /** Suspension is left out, since it can be reversed and conversation keys mustn't change when it is. */
    static String notGone(String alias) {
        // Actor domains can include a port; instance domain blocks apply to the DNS name, as DomainBlocks does. Only
        // a suspension hides a server.
        var host = "regexp_replace(" + alias + ".domain, ':[0-9]+$', '')";
        return alias + ".deleted_at IS NULL AND (" + alias
                + ".is_local OR NOT EXISTS (SELECT 1 FROM domain_block b WHERE b.severity = 'suspend' AND (" + host
                + " = b.domain OR right(" + host + ", length(b.domain) + 1) = '.' || b.domain)))";
    }

    static String visible(String status) {
        return "(" + status + ".visibility IN ('public', 'unlisted') OR " + status + ".actor_id = :viewer OR EXISTS ("
                + "SELECT 1 FROM status_mention m WHERE m.status_id = " + status + ".id AND m.actor_id = :viewer) OR ("
                + status + ".visibility = 'private' AND EXISTS (SELECT 1 FROM follow f WHERE f.follower_id = :viewer"
                + " AND f.followed_id = " + status + ".actor_id AND f.state = 'accepted')))";
    }

    static void cursors(StringBuilder sql, Map<String, Object> parameters, InboxPage page, String column) {
        if (page.maxId() != null) {
            sql.append(" AND ").append(column).append(" < :maxId");
            parameters.put("maxId", page.maxId());
        }
        var after = page.minId() == null ? page.sinceId() : page.minId();
        if (after != null) {
            sql.append(" AND ").append(column).append(" > :after");
            parameters.put("after", after);
        }
    }
}
