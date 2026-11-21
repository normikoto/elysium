package dev.biribiri.elysium.notification.internal;

import dev.biribiri.elysium.actor.ActorSql;
import dev.biribiri.elysium.graph.HiddenAccounts;
import dev.biribiri.elysium.notification.Page;

import java.util.Map;

/** Apply eligibility before paging or counting, including the viewer's blocks and mutes. */
final class NotificationSql {
    private NotificationSql() {
    }

    static String active(String alias) {
        return ActorSql.active(alias) + " AND " + ActorSql.serverNotSuspended(alias);
    }

    /** Suspension is left out, since it can be reversed and conversation keys mustn't change when it is. */
    static String notGone(String alias) {
        return alias + ".deleted_at IS NULL AND " + ActorSql.serverNotSuspended(alias);
    }

    /**
     * Leaves out actors the viewer has blocked, muted with their notifications, or blocked the server of, and those that
     * block the viewer. This is read each time, so that what was stored before a mute comes back once it ends.
     */
    static String notHidden(String alias) {
        return HiddenAccounts.visibleInNotifications(alias);
    }

    static void cursors(StringBuilder sql, Map<String, Object> parameters, Page page, String column) {
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
