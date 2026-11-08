package dev.biribiri.elysium.inbox.internal;

import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.inbox.InboxPage;
import dev.biribiri.elysium.inbox.Notification;
import dev.biribiri.elysium.inbox.NotificationFilter;
import dev.biribiri.elysium.inbox.NotificationGroup;
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
    private static final String FROM = """
             FROM inbox_notification n JOIN actor a ON a.id = n.sender_id
             LEFT JOIN status s ON s.id = n.status_id
             WHERE n.recipient_id = :viewer AND NOT n.dismissed AND
            """ + InboxSql.active("a") + " AND (n.status_id IS NULL OR (s.id IS NOT NULL AND "
            + InboxSql.visible("s") + "))";
    private static final String GROUP = """
            CASE WHEN n.type = ANY(CAST(:groupedTypes AS text[])) AND n.group_key IS NOT NULL
                 THEN n.group_key ELSE 'ungrouped-' || n.id END
            """;
    private static final RowMapper<Notification> MAPPER = (rs, row) -> new Notification(
            rs.getObject("id", UUID.class), rs.getString("type"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getObject("sender_id", UUID.class),
            rs.getObject("status_id", UUID.class), rs.getString("group_key"));

    private final JdbcClient jdbc;

    NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Serializes inbox mutations and ID assignment until commit, including across application instances. */
    public void lock() {
        jdbc.sql("SELECT last_id FROM inbox_sequence WHERE singleton FOR UPDATE").query(UUID.class).single();
    }

    public void insert(UUID recipient, UUID sender, String type, UUID source, UUID status, UUID follow) {
        insert(recipient, sender, type, source, status, follow, null, null);
    }

    public void insertFavourite(UUID recipient, UUID sender, UUID like, UUID status) {
        insert(recipient, sender, "favourite", like, status, null, like, null);
    }

    /**
     * @param reblog the boost, which takes its notification with it when it's removed
     * @param status the status it boosts
     */
    public void insertReblog(UUID recipient, UUID sender, UUID reblog, UUID status) {
        insert(recipient, sender, "reblog", reblog, status, null, null, reblog);
    }

    private void insert(
            UUID recipient, UUID sender, String type, UUID source, UUID status, UUID follow, UUID like, UUID reblog) {
        if (jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM inbox_notification
                        WHERE recipient_id = :recipient AND type = :type AND source_id = :source)
                        """).param("recipient", recipient).param("type", type).param("source", source)
                .query(Boolean.class).single()) {
            return;
        }
        var previous = jdbc.sql("SELECT last_id FROM inbox_sequence WHERE singleton").query(UUID.class).single();
        var now = Instant.now();
        var millis = Math.max(now.toEpochMilli(), (previous.getMostSignificantBits() >>> 16) + 1);
        var id = Ids.timeOrdered(Instant.ofEpochMilli(millis));
        String group = type.equals("favourite") || type.equals("reblog") ? type + "-" + status : null;
        if (type.equals("follow")) {
            var hour = now.getEpochSecond() / 3600;
            var start = jdbc.sql("""
                            INSERT INTO inbox_follow_group (recipient_id, start_hour) VALUES (:recipient, :hour)
                            ON CONFLICT (recipient_id) DO UPDATE SET start_hour = CASE
                            WHEN EXCLUDED.start_hour >= inbox_follow_group.start_hour + 12
                            THEN EXCLUDED.start_hour ELSE inbox_follow_group.start_hour END RETURNING start_hour
                            """).param("recipient", recipient).param("hour", hour).query(Long.class).single();
            group = "follow-" + start;
        }
        jdbc.sql("""
                        INSERT INTO inbox_notification
                        (id, recipient_id, sender_id, type, source_id, status_id, follow_id, like_id, reblog_id,
                         created_at, group_key)
                        VALUES (:id, :recipient, :sender, :type, :source, :status, :follow, :like, :reblog, :created,
                                :group)
                        ON CONFLICT (recipient_id, type, source_id) DO NOTHING
                        """).param("id", id).param("recipient", recipient).param("sender", sender).param("type", type)
                .param("source", source).param("status", status, Types.OTHER).param("follow", follow, Types.OTHER)
                .param("like", like, Types.OTHER).param("reblog", reblog, Types.OTHER).param("created", now.atOffset(ZoneOffset.UTC)).param("group", group, Types.VARCHAR).update();
        jdbc.sql("UPDATE inbox_sequence SET last_id = :id WHERE singleton").param("id", id).update();
    }

    public List<Notification> page(UUID viewer, NotificationFilter filter, InboxPage page) {
        var parameters = parameters(viewer, filter);
        var sql = new StringBuilder("SELECT n.*" + filtered(filter));
        InboxSql.cursors(sql, parameters, page, "n.id");
        sql.append(page.minId() == null ? " ORDER BY n.id DESC" : " ORDER BY n.id ASC").append(" LIMIT :limit");
        parameters.put("limit", page.limit());
        var found = jdbc.sql(sql.toString()).params(parameters).query(MAPPER).list();
        return page.minId() == null ? found : found.reversed();
    }

    public Optional<Notification> find(UUID viewer, UUID id) {
        return jdbc.sql("SELECT n.*" + FROM + " AND n.id = :id").param("viewer", viewer).param("id", id)
                .query(MAPPER).optional();
    }

    public boolean dismiss(UUID viewer, UUID id) {
        return jdbc.sql("UPDATE inbox_notification SET dismissed = TRUE WHERE recipient_id = :viewer AND id = :id AND NOT dismissed")
                .param("viewer", viewer).param("id", id).update() != 0;
    }

    public void clear(UUID viewer) {
        jdbc.sql("UPDATE inbox_notification SET dismissed = TRUE WHERE recipient_id = :viewer AND NOT dismissed")
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
    private List<Notification> representatives(UUID viewer, NotificationFilter filter, InboxPage page, List<String> groupedTypes) {
        var parameters = parameters(viewer, filter);
        parameters.put("groupedTypes", groupedTypes(groupedTypes));
        parameters.put("limit", page.limit());
        var eligible = new StringBuilder("SELECT n.*, " + GROUP + " AS effective_key" + filtered(filter));
        InboxSql.cursors(eligible, parameters, page, "n.id");
        var ascending = page.minId() != null;
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
                SELECT n.id, n.type, n.created_at, n.sender_id, n.status_id, chosen.effective_key AS group_key
                FROM chosen JOIN inbox_notification n ON n.id = chosen.id ORDER BY n.id %s
                """.formatted(eligible, direction, comparison, direction, direction);
        var found = jdbc.sql(sql).params(parameters).query(MAPPER).list();
        return ascending ? found.reversed() : found;
    }

    public List<NotificationGroup> groups(UUID viewer, NotificationFilter filter, InboxPage page, List<String> groupedTypes) {
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
                    detail.min(), detail.latest(), detail.at(), detail.sample(), notification.statusId(), notification.id());
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
                members.stream().map(Notification::accountId).toList(), first.statusId(), first.id()));
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
        jdbc.sql("UPDATE inbox_notification SET dismissed = TRUE WHERE recipient_id = :viewer AND NOT dismissed AND " + condition)
                .param("viewer", viewer).params(groupParameters(key)).update();
    }

    public long countGroups(UUID viewer, NotificationFilter filter, int limit, UUID after, List<String> types) {
        return representatives(viewer, filter, new InboxPage(limit, null, null, after), types).size();
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
