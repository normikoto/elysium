package dev.biribiri.elysium.notification.internal;

import dev.biribiri.elysium.actor.IdPage;
import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.notification.NotificationRequest;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationRequestRepository {
    /**
     * Only filtered mentions and quotes make a request, as on Mastodon. Other filtered notifications are only seen with
     * {@code include_filtered}.
     */
    private static final String REQUESTED_TYPES = "('mention', 'quote')";
    private static final String WAITING = """
            n.recipient_id = r.recipient_id AND n.sender_id = r.sender_id AND n.filtered AND NOT n.dismissed
            AND n.type IN\s""" + REQUESTED_TYPES;
    private static final String SELECT = """
            SELECT r.id, r.sender_id, r.created_at, latest.created_at AS updated_at, latest.status_id AS last_status_id,
                   (SELECT count(*) FROM (SELECT 1 FROM notification n WHERE %1$s LIMIT %2$d) waiting) AS notifications_count
            FROM notification_request r JOIN actor a ON a.id = r.sender_id
            CROSS JOIN LATERAL (SELECT n.created_at, n.status_id FROM notification n WHERE %1$s ORDER BY n.id DESC LIMIT 1) latest
            WHERE r.recipient_id = :viewer AND %3$s AND %4$s
            """.formatted(WAITING, NotificationRequest.MAX_COUNT, NotificationSql.active("a"), NotificationSql.notHidden("a"));
    private static final RowMapper<NotificationRequest> MAPPER = (rs, row) -> new NotificationRequest(
            rs.getObject("id", UUID.class), rs.getObject("sender_id", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant(), rs.getLong("notifications_count"),
            rs.getObject("last_status_id", UUID.class));

    private final JdbcClient jdbc;

    NotificationRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void add(UUID recipient, UUID sender) {
        jdbc.sql("""
                        INSERT INTO notification_request (id, recipient_id, sender_id, created_at)
                        VALUES (:id, :recipient, :sender, :created) ON CONFLICT (recipient_id, sender_id) DO NOTHING
                        """)
                .param("id", Ids.timeOrdered(Instant.now())).param("recipient", recipient).param("sender", sender)
                .param("created", Instant.now().atOffset(ZoneOffset.UTC))
                .update();
    }

    public List<NotificationRequest> page(UUID viewer, IdPage page) {
        var parameters = new HashMap<String, Object>();
        parameters.put("viewer", viewer);
        var sql = SELECT + page.cursors("r.id", parameters) + page.orderAndLimit("r.id", parameters);
        return page.newestFirst(jdbc.sql(sql).params(parameters).query(MAPPER).list());
    }

    public Optional<NotificationRequest> find(UUID viewer, UUID id) {
        return jdbc.sql(SELECT + " AND r.id = :id").param("viewer", viewer).param("id", id).query(MAPPER).optional();
    }

    public NotificationRequest.Summary summary(UUID viewer) {
        return jdbc.sql("SELECT count(*), COALESCE(sum(notifications_count), 0) FROM (" + SELECT + " LIMIT "
                        + NotificationRequest.MAX_COUNT + ") waiting")
                .param("viewer", viewer)
                .query((rs, row) -> new NotificationRequest.Summary(rs.getLong(1), rs.getLong(2))).single();
    }

    public void permit(UUID recipient, UUID sender) {
        jdbc.sql("""
                        INSERT INTO notification_permission (recipient_id, sender_id) VALUES (:recipient, :sender)
                        ON CONFLICT DO NOTHING
                        """)
                .param("recipient", recipient).param("sender", sender).update();
    }

    /**
     * @return the statuses of the mentions that are no longer filtered, so that their conversations can be brought in
     */
    public List<UUID> unfilter(UUID recipient, UUID sender) {
        return jdbc.sql("""
                        UPDATE notification SET filtered = FALSE
                        WHERE recipient_id = :recipient AND sender_id = :sender AND filtered AND NOT dismissed
                        RETURNING CASE WHEN type = 'mention' THEN status_id END
                        """)
                .param("recipient", recipient).param("sender", sender)
                .query(UUID.class).list().stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    /**
     * Dismissing keeps the notifications, as {@code dismissed} does elsewhere, so that a retried event can't add them
     * again.
     */
    public void dismissFiltered(UUID recipient, UUID sender) {
        jdbc.sql("""
                        UPDATE notification SET dismissed = TRUE
                        WHERE recipient_id = :recipient AND sender_id = :sender AND filtered AND NOT dismissed
                        """)
                .param("recipient", recipient).param("sender", sender).update();
    }

    public void delete(UUID recipient, UUID sender) {
        jdbc.sql("DELETE FROM notification_request WHERE recipient_id = :recipient AND sender_id = :sender")
                .param("recipient", recipient).param("sender", sender).update();
    }

    /**
     * Requests and permissions either way between two accounts, once one has blocked the other.
     */
    public void deleteBetween(UUID one, UUID other) {
        for (var table : List.of("notification_request", "notification_permission")) {
            jdbc.sql("DELETE FROM " + table + """
                             WHERE (recipient_id = :one AND sender_id = :other) OR (recipient_id = :other AND sender_id = :one)
                            """)
                    .param("one", one).param("other", other).update();
        }
    }

    public void removeForActors(List<UUID> actorIds) {
        var ids = actorIds.stream().map(UUID::toString).toArray(String[]::new);
        for (var table : List.of("notification_request", "notification_permission")) {
            jdbc.sql("DELETE FROM " + table + """
                             WHERE recipient_id = ANY(CAST(:ids AS uuid[])) OR sender_id = ANY(CAST(:ids AS uuid[]))
                            """)
                    .param("ids", ids).update();
        }
    }
}
