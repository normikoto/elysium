package dev.biribiri.elysium.notification.internal;

import dev.biribiri.elysium.actor.IdPage;
import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.auth.AdminRole;
import dev.biribiri.elysium.content.StatusSql;
import dev.biribiri.elysium.notification.Notification;
import dev.biribiri.elysium.notification.NotificationFilter;
import dev.biribiri.elysium.notification.NotificationGroup;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationRepository {
    /**
     * Only admins see these, and only while they're admins, since a report can include statuses its audience didn't
     * include them in. A report is for admins to act on whoever sent it, so a block or mute of the reporter doesn't hide it.
     */
    private static final String ADMIN_REPORT = "admin.report";
    private static final String FROM = """
             FROM notification n JOIN actor a ON a.id = n.sender_id
             LEFT JOIN status s ON s.id = n.status_id
             LEFT JOIN actor t ON t.id = n.target_id
             WHERE n.recipient_id = :viewer AND NOT n.dismissed AND
            """ + NotificationSql.active("a")
            + " AND (n.target_id IS NULL OR (t.id IS NOT NULL AND " + NotificationSql.active("t") + "))"
            + " AND (n.type <> '" + ADMIN_REPORT + "' OR " + AdminRole.viewerIsAdmin() + ")"
            + " AND (n.type = '" + ADMIN_REPORT + "' OR " + NotificationSql.notHidden("a") + ")"
            + " AND (n.status_id IS NULL OR (s.id IS NOT NULL AND " + StatusSql.visibleTo(":viewer") + "))";
    private static final String GROUP = """
            CASE WHEN n.type = ANY(CAST(:groupedTypes AS text[])) AND n.group_key IS NOT NULL
                 THEN n.group_key ELSE 'ungrouped-' || n.id END
            """;
    private static final RowMapper<Notification> MAPPER = (rs, row) -> new Notification(
            rs.getObject("id", UUID.class), rs.getString("type"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getObject("sender_id", UUID.class),
            rs.getObject("status_id", UUID.class), rs.getString("group_key"), rs.getObject("report_id", UUID.class),
            rs.getObject("reaction_id", UUID.class), rs.getObject("target_id", UUID.class));

    private final JdbcClient jdbc;

    NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Serializes changes to notifications and conversations and ID assignment until commit, including across application instances. */
    public void lock() {
        jdbc.sql("SELECT last_id FROM notification_sequence WHERE singleton FOR UPDATE").query(UUID.class).single();
    }

    public Optional<UUID> insert(UUID recipient, UUID sender, String type, UUID source, UUID status, UUID follow) {
        return insert(recipient, sender, type, source, status, follow, null, null, null);
    }

    /**
     * Whether the recipient hides what the sender does, as {@link NotificationSql#notHidden} decides.
     */
    public boolean isHidden(UUID recipient, UUID sender) {
        return jdbc.sql("SELECT NOT " + NotificationSql.notHidden("a") + " FROM actor a WHERE a.id = :sender")
                .param("viewer", recipient)
                .param("sender", sender)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    /**
     * Removes the notifications between two accounts, either way, once one has blocked the other, as on Mastodon.
     */
    public void deleteBetween(UUID one, UUID other) {
        jdbc.sql("""
                        DELETE FROM notification
                        WHERE (recipient_id = :one AND sender_id = :other) OR (recipient_id = :other AND sender_id = :one)
                        """)
                .param("one", one)
                .param("other", other)
                .update();
    }

    /**
     * Removes the notification of a request to follow, once it's been answered.
     */
    public void deleteFollowRequest(UUID followId) {
        jdbc.sql("DELETE FROM notification WHERE follow_id = :follow AND type = 'follow_request'")
                .param("follow", followId)
                .update();
    }

    /**
     * @param sender the reporter, which for a report from another server is whoever sent it there
     */
    public Optional<UUID> insertReport(UUID recipient, UUID sender, UUID report) {
        return insert(recipient, sender, ADMIN_REPORT, report, null, null, null, null, report);
    }

    /**
     * Each reaction is its own, since grouping them would lose which emoji each one is.
     */
    public Optional<UUID> insertReaction(UUID recipient, UUID sender, UUID reaction, UUID status) {
        return insert(recipient, sender, Notification.REACTION, reaction, status, null, null, null, null, reaction, null);
    }

    public Optional<UUID> insertFavourite(UUID recipient, UUID sender, UUID like, UUID status) {
        return insert(recipient, sender, "favourite", like, status, null, like, null, null);
    }

    /**
     * @param reblog the boost, which takes its notification with it when it's removed
     * @param status the status it boosts
     */
    public Optional<UUID> insertReblog(UUID recipient, UUID sender, UUID reblog, UUID status) {
        return insert(recipient, sender, "reblog", reblog, status, null, null, reblog, null);
    }

    /**
     * @param status the quote
     */
    public Optional<UUID> insertQuote(UUID recipient, UUID sender, UUID status) {
        return insert(recipient, sender, "quote", status, status, null, null, null, null);
    }

    /**
     * @param status the quote that waits for the recipient to review it
     */
    public Optional<UUID> insertPendingQuote(UUID recipient, UUID sender, UUID status) {
        return insert(recipient, sender, "pending.quote", status, status, null, null, null, null);
    }

    /**
     * Removes the notification that a quote was approved, once the status no longer quotes what it was about.
     */
    public void deleteQuote(UUID recipient, UUID status) {
        jdbc.sql("DELETE FROM notification WHERE recipient_id = :recipient AND type = 'quote' AND source_id = :status")
                .param("recipient", recipient)
                .param("status", status)
                .update();
    }

    /**
     * Removes the notification asking to review a quote, once it's been answered, or asked for again.
     */
    public void deletePendingQuote(UUID recipient, UUID status) {
        jdbc.sql("DELETE FROM notification WHERE recipient_id = :recipient AND type = 'pending.quote' AND source_id = :status")
                .param("recipient", recipient)
                .param("status", status)
                .update();
    }

    /**
     * @param source what identifies this edit of the quoted status, so that a repeat adds nothing
     * @param status the recipient's quote
     */
    public Optional<UUID> insertQuotedUpdate(UUID recipient, UUID sender, UUID source, UUID status) {
        return insert(recipient, sender, "quoted_update", source, status, null, null, null, null);
    }

    /**
     * @param source what identifies this edit of the boosted status, so that a repeat adds nothing
     * @param status the boosted status
     * @param reblog the recipient's boost, which takes this with it when it's removed
     */
    public Optional<UUID> insertUpdate(UUID recipient, UUID sender, UUID source, UUID status, UUID reblog) {
        return insert(recipient, sender, "update", source, status, null, null, reblog, null);
    }

    /**
     * @param status the poll's status, which is also what identifies this, since a poll ends once
     */
    public Optional<UUID> insertPoll(UUID recipient, UUID sender, UUID status) {
        return insert(recipient, sender, "poll", status, status, null, null, null, null);
    }

    /**
     * @param sender the account that moved
     * @param move   what identifies the move, so that a repeat adds nothing
     * @param target the account it moved to
     */
    public Optional<UUID> insertMove(UUID recipient, UUID sender, UUID move, UUID target) {
        return insert(recipient, sender, "move", move, null, null, null, null, null, null, target);
    }

    /**
     * Nothing is added from a sender the recipient hides notifications from, as on Mastodon, so ending a mute doesn't bring
     * back what was sent during it.
     *
     * @return the new notification, or empty if nothing was added
     */
    private Optional<UUID> insert(
            UUID recipient, UUID sender, String type, UUID source, UUID status, UUID follow, UUID like, UUID reblog,
            UUID report) {
        return insert(recipient, sender, type, source, status, follow, like, reblog, report, null, null);
    }

    private Optional<UUID> insert(
            UUID recipient, UUID sender, String type, UUID source, UUID status, UUID follow, UUID like, UUID reblog,
            UUID report, UUID reaction, UUID target) {
        if (jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM notification
                        WHERE recipient_id = :recipient AND type = :type AND source_id = :source)
                        """).param("recipient", recipient).param("type", type).param("source", source)
                .query(Boolean.class).single() || (!type.equals(ADMIN_REPORT) && isHidden(recipient, sender))) {
            return Optional.empty();
        }
        var previous = jdbc.sql("SELECT last_id FROM notification_sequence WHERE singleton").query(UUID.class).single();
        var now = Instant.now();
        var millis = Math.max(now.toEpochMilli(), (previous.getMostSignificantBits() >>> 16) + 1);
        var id = Ids.timeOrdered(Instant.ofEpochMilli(millis));
        String group = type.equals("favourite") || type.equals("reblog") ? type + "-" + status : null;
        if (type.equals("follow")) {
            var hour = now.getEpochSecond() / 3600;
            var start = jdbc.sql("""
                            INSERT INTO notification_follow_group (recipient_id, start_hour) VALUES (:recipient, :hour)
                            ON CONFLICT (recipient_id) DO UPDATE SET start_hour = CASE
                            WHEN EXCLUDED.start_hour >= notification_follow_group.start_hour + 12
                            THEN EXCLUDED.start_hour ELSE notification_follow_group.start_hour END RETURNING start_hour
                            """).param("recipient", recipient).param("hour", hour).query(Long.class).single();
            group = "follow-" + start;
        }
        var inserted = jdbc.sql("""
                        INSERT INTO notification
                        (id, recipient_id, sender_id, type, source_id, status_id, follow_id, like_id, reblog_id,
                         report_id, reaction_id, target_id, created_at, group_key)
                        VALUES (:id, :recipient, :sender, :type, :source, :status, :follow, :like, :reblog, :report,
                                :reaction, :target, :created, :group)
                        ON CONFLICT (recipient_id, type, source_id) DO NOTHING
                        """).param("id", id).param("recipient", recipient).param("sender", sender).param("type", type)
                .param("source", source).param("status", status, Types.OTHER).param("follow", follow, Types.OTHER)
                .param("like", like, Types.OTHER).param("reblog", reblog, Types.OTHER).param("report", report, Types.OTHER).param("reaction", reaction, Types.OTHER).param("target", target, Types.OTHER).param("created", now.atOffset(ZoneOffset.UTC)).param("group", group, Types.VARCHAR).update();
        if (inserted == 0) {
            return Optional.empty();
        }
        jdbc.sql("UPDATE notification_sequence SET last_id = :id WHERE singleton").param("id", id).update();
        return Optional.of(id);
    }

    public List<Notification> page(UUID viewer, NotificationFilter filter, IdPage page) {
        var parameters = parameters(viewer, filter);
        var sql = "SELECT n.*" + filtered(filter) + page.cursors("n.id", parameters) + page.orderAndLimit("n.id", parameters);
        return page.newestFirst(jdbc.sql(sql).params(parameters).query(MAPPER).list());
    }

    public Optional<Notification> find(UUID viewer, UUID id) {
        return jdbc.sql("SELECT n.*" + FROM + " AND n.id = :id").param("viewer", viewer).param("id", id)
                .query(MAPPER).optional();
    }

    public boolean dismiss(UUID viewer, UUID id) {
        return jdbc.sql("UPDATE notification SET dismissed = TRUE WHERE recipient_id = :viewer AND id = :id AND NOT dismissed")
                .param("viewer", viewer).param("id", id).update() != 0;
    }

    public void clear(UUID viewer) {
        jdbc.sql("UPDATE notification SET dismissed = TRUE WHERE recipient_id = :viewer AND NOT dismissed")
                .param("viewer", viewer).update();
    }

    public long count(UUID viewer, NotificationFilter filter, int limit, UUID after) {
        var parameters = parameters(viewer, filter);
        var sql = new StringBuilder("SELECT count(*) FROM (SELECT 1" + filtered(filter));
        if (after != null) {
            sql.append(" AND n.id > :after");
            parameters.put("after", after);
        }
        sql.append(" LIMIT :limit) unread");
        parameters.put("limit", limit);
        return jdbc.sql(sql.toString()).params(parameters).query(Long.class).single();
    }

    /** Fetch one representative per distinct group, stopping at the requested number instead of scanning all history. */
    private List<Notification> representatives(UUID viewer, NotificationFilter filter, IdPage page, List<String> groupedTypes) {
        var parameters = parameters(viewer, filter);
        parameters.put("groupedTypes", groupedTypes(groupedTypes));
        parameters.put("limit", page.limit());
        var eligible = "SELECT n.*, " + GROUP + " AS effective_key" + filtered(filter) + page.cursors("n.id", parameters);
        var ascending = page.fromOldest();
        var direction = ascending ? "ASC" : "DESC";
        var comparison = ascending ? ">" : "<";
        var sql = """
                WITH RECURSIVE eligible AS NOT MATERIALIZED (%s), chosen AS (
                    (SELECT e.id, e.effective_key, ARRAY[e.effective_key] AS visited
                     FROM eligible e ORDER BY e.id %s LIMIT 1)
                    UNION ALL
                    SELECT next.id, next.effective_key, chosen.visited || next.effective_key
                    FROM chosen CROSS JOIN LATERAL (
                        SELECT e.id, e.effective_key FROM eligible e
                        WHERE e.id %s chosen.id AND NOT (e.effective_key = ANY(chosen.visited))
                        ORDER BY e.id %s LIMIT 1
                    ) next WHERE cardinality(chosen.visited) < :limit
                )
                SELECT n.id, n.type, n.created_at, n.sender_id, n.status_id, n.report_id, n.reaction_id, n.target_id,
                       chosen.effective_key AS group_key
                FROM chosen JOIN notification n ON n.id = chosen.id ORDER BY n.id %s
                """.formatted(eligible, direction, comparison, direction, direction);
        return page.newestFirst(jdbc.sql(sql).params(parameters).query(MAPPER).list());
    }

    public List<NotificationGroup> groups(UUID viewer, NotificationFilter filter, IdPage page, List<String> groupedTypes) {
        var found = representatives(viewer, filter, page, groupedTypes);
        if (found.isEmpty()) {
            return List.of();
        }
        var parameters = parameters(viewer, filter);
        parameters.put("groupedTypes", groupedTypes(groupedTypes));
        parameters.put("keys", found.stream().map(Notification::groupKey).toArray(String[]::new));
        parameters.put("individuals", found.stream().map(Notification::groupKey).filter(key -> key.startsWith("ungrouped-"))
                .map(key -> key.substring("ungrouped-".length())).toArray(String[]::new));
        parameters.put("upper", found.getFirst().id());
        parameters.put("lower", found.getLast().id());
        var sql = """
                WITH eligible AS (SELECT n.*, %s AS effective_key %s AND n.id <= :upper
                    AND (n.group_key = ANY(CAST(:keys AS text[])) OR n.id = ANY(CAST(:individuals AS uuid[]))))
                SELECT effective_key, count(*) AS total,
                       max(id::text)::uuid AS latest,
                       (SELECT sample.created_at FROM eligible sample WHERE sample.effective_key = eligible.effective_key
                        ORDER BY sample.id DESC LIMIT 1) AS latest_at,
                       min(id::text) FILTER (WHERE id >= :lower) AS page_min,
                       ARRAY(SELECT sample.sender_id FROM eligible sample WHERE sample.effective_key = eligible.effective_key
                             ORDER BY sample.id DESC LIMIT 8) AS sample
                FROM eligible WHERE effective_key = ANY(CAST(:keys AS text[])) GROUP BY effective_key
                """.formatted(GROUP, filtered(filter));
        var details = new HashMap<String, Details>();
        jdbc.sql(sql).params(parameters).query((rs, n) -> {
            details.put(rs.getString("effective_key"), new Details(rs.getLong("total"),
                    rs.getObject("latest", UUID.class), rs.getObject("latest_at", OffsetDateTime.class).toInstant(),
                    UUID.fromString(rs.getString("page_min")), Arrays.asList((UUID[]) rs.getArray("sample").getArray())));
            return true;
        }).list();
        return found.stream().map(notification -> {
            var detail = details.get(notification.groupKey());
            return new NotificationGroup(notification.groupKey(), detail.count(), notification.type(), detail.latest(),
                    detail.min(), detail.latest(), detail.at(), detail.sample(), notification.statusId(), notification.id(),
                    notification.reportId());
        }).toList();
    }

    public Optional<NotificationGroup> group(UUID viewer, String key) {
        var members = jdbc.sql("SELECT n.*" + FROM + groupCondition(key) + " ORDER BY n.id DESC LIMIT 8")
                .param("viewer", viewer).params(groupParameters(key)).query(MAPPER).list();
        if (members.isEmpty()) return Optional.empty();
        var count = jdbc.sql("SELECT count(*)" + FROM + groupCondition(key)).param("viewer", viewer).params(groupParameters(key))
                .query(Long.class).single();
        var first = members.getFirst();
        return Optional.of(new NotificationGroup(key, count, first.type(), first.id(), null, null, null,
                members.stream().map(Notification::accountId).toList(), first.statusId(), first.id(), first.reportId()));
    }

    public List<UUID> groupAccounts(UUID viewer, String key) {
        return jdbc.sql("SELECT n.sender_id" + FROM + groupCondition(key) + " GROUP BY n.sender_id ORDER BY max(n.id::text) DESC")
                .param("viewer", viewer).params(groupParameters(key)).query(UUID.class).list();
    }

    private static String groupCondition(String key) {
        return key.startsWith("ungrouped-") ? " AND n.id = :groupId" : " AND n.group_key = :key";
    }

    private static Map<String, Object> groupParameters(String key) {
        if (!key.startsWith("ungrouped-")) return Map.of("key", key);
        return Map.of("groupId", Ids.parse(key.substring("ungrouped-".length())).orElse(new UUID(0, 0)));
    }

    public void dismissGroup(UUID viewer, String key) {
        var condition = key.startsWith("ungrouped-") ? "id = :groupId" : "group_key = :key";
        jdbc.sql("UPDATE notification SET dismissed = TRUE WHERE recipient_id = :viewer AND NOT dismissed AND " + condition)
                .param("viewer", viewer).params(groupParameters(key)).update();
    }

    public long countGroups(UUID viewer, NotificationFilter filter, int limit, UUID after, List<String> types) {
        return representatives(viewer, filter, new IdPage(limit, null, null, after), types).size();
    }

    private static String filtered(NotificationFilter filter) {
        return FROM + (filter.types().isEmpty() ? "" : " AND n.type = ANY(CAST(:types AS text[]))")
                + (filter.excludeTypes().isEmpty() ? "" : " AND NOT (n.type = ANY(CAST(:excluded AS text[])))")
                + (filter.accountId() == null ? "" : " AND n.sender_id = :account");
    }

    private static Map<String, Object> parameters(UUID viewer, NotificationFilter filter) {
        var parameters = new HashMap<String, Object>();
        parameters.put("viewer", viewer);
        if (!filter.types().isEmpty()) parameters.put("types", filter.types().toArray(String[]::new));
        if (!filter.excludeTypes().isEmpty()) parameters.put("excluded", filter.excludeTypes().toArray(String[]::new));
        if (filter.accountId() != null) parameters.put("account", filter.accountId());
        return parameters;
    }

    private static String[] groupedTypes(List<String> types) {
        return types == null || types.isEmpty() ? new String[]{"follow", "favourite", "reblog"} : types.toArray(String[]::new);
    }

    private record Details(long count, UUID latest, Instant at, UUID min, List<UUID> sample) {
    }
}
