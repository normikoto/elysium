package dev.biribiri.elysium.moderation.internal;

import dev.biribiri.elysium.moderation.Report;
import dev.biribiri.elysium.moderation.ReportCategory;
import dev.biribiri.elysium.moderation.ReportPage;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReportRepository {

    private static final String COLUMNS = """
            id, reporter_id, target_id, comment, category, forwarded, activity_uri, assigned_to, resolved_by, created_at,
            updated_at, resolved_at""";

    private static final RowMapper<Report> MAPPER = (rs, row) -> map(rs, List.of());

    private final JdbcClient jdbc;

    ReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return whether it was saved, which it isn't if it repeats a {@code Flag} that's already been stored for this target
     */
    public boolean insert(Report report) {
        var inserted = jdbc.sql("""
                        INSERT INTO report (id, reporter_id, target_id, comment, category, forwarded, activity_uri,
                                            created_at, updated_at)
                        VALUES (:id, :reporterId, :targetId, :comment, :category, :forwarded, :activityUri,
                                :createdAt, :createdAt)
                        ON CONFLICT DO NOTHING
                        """)
                .param("id", report.id())
                .param("reporterId", report.reporterId())
                .param("targetId", report.targetId())
                .param("comment", report.comment())
                .param("category", report.category().value())
                .param("forwarded", report.forwarded())
                .param("activityUri", report.activityUri())
                .param("createdAt", report.createdAt().atOffset(ZoneOffset.UTC))
                .update() == 1;
        if (inserted) {
            for (var statusId : report.statusIds()) {
                jdbc.sql("INSERT INTO report_status (report_id, status_id) VALUES (:reportId, :statusId)")
                        .param("reportId", report.id())
                        .param("statusId", statusId)
                        .update();
            }
        }
        return inserted;
    }

    public Optional<Report> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM report WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional()
                .map(report -> withStatuses(List.of(report)).getFirst());
    }

    public List<Report> findPage(ReportPage page) {
        var sql = new StringBuilder("SELECT " + COLUMNS + " FROM report WHERE resolved_at IS ")
                .append(page.resolved() ? "NOT NULL" : "NULL");
        var parameters = new HashMap<String, Object>();
        // Predicates are only added when needed, because PostgreSQL can't determine the type of a null parameter.
        if (page.reporterId() != null) {
            sql.append(" AND reporter_id = :reporterId");
            parameters.put("reporterId", page.reporterId());
        }
        if (page.targetId() != null) {
            sql.append(" AND target_id = :targetId");
            parameters.put("targetId", page.targetId());
        }
        if (page.maxId() != null) {
            sql.append(" AND id < :maxId");
            parameters.put("maxId", page.maxId());
        }
        var ascending = page.minId() != null;
        var after = ascending ? page.minId() : page.sinceId();
        if (after != null) {
            sql.append(" AND id > :after");
            parameters.put("after", after);
        }
        sql.append(ascending ? " ORDER BY id ASC" : " ORDER BY id DESC").append(" LIMIT :limit");
        parameters.put("limit", page.limit());

        var reports = jdbc.sql(sql.toString()).params(parameters).query(MAPPER).list();
        return withStatuses(ascending ? reports.reversed() : reports);
    }

    public long countOpen() {
        return jdbc.sql("SELECT count(*) FROM report WHERE resolved_at IS NULL").query(Long.class).single();
    }

    public List<Report> findByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return withStatuses(jdbc.sql("SELECT " + COLUMNS + " FROM report WHERE id IN (:ids)")
                .param("ids", ids)
                .query(MAPPER)
                .list());
    }

    public boolean assign(UUID id, UUID adminId) {
        return jdbc.sql("UPDATE report SET assigned_to = :adminId, updated_at = now() WHERE id = :id")
                .param("id", id).param("adminId", adminId).update() == 1;
    }

    public boolean unassign(UUID id) {
        return jdbc.sql("UPDATE report SET assigned_to = NULL, updated_at = now() WHERE id = :id")
                .param("id", id).update() == 1;
    }

    /**
     * Resolving one that's resolved already keeps who did it first.
     */
    public boolean resolve(UUID id, UUID adminId) {
        return jdbc.sql("""
                        UPDATE report SET resolved_at = coalesce(resolved_at, now()),
                                          resolved_by = CASE WHEN resolved_at IS NULL THEN :adminId ELSE resolved_by END,
                                          updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", id).param("adminId", adminId).update() == 1;
    }

    public boolean reopen(UUID id) {
        return jdbc.sql("UPDATE report SET resolved_at = NULL, resolved_by = NULL, updated_at = now() WHERE id = :id")
                .param("id", id).update() == 1;
    }

    public boolean updateCategory(UUID id, ReportCategory category) {
        return jdbc.sql("UPDATE report SET category = :category, updated_at = now() WHERE id = :id")
                .param("id", id).param("category", category.value()).update() == 1;
    }

    /**
     * Removes the reports by and about a domain's remote actors.
     */
    public void deleteForDomain(String domain) {
        jdbc.sql("""
                        DELETE FROM report r USING actor a
                        WHERE NOT a.is_local AND a.domain = :domain AND (r.reporter_id = a.id OR r.target_id = a.id)
                        """)
                .param("domain", domain)
                .update();
    }

    public void deleteForActor(UUID actorId) {
        jdbc.sql("DELETE FROM report WHERE reporter_id = :actorId OR target_id = :actorId")
                .param("actorId", actorId)
                .update();
    }

    private List<Report> withStatuses(List<Report> reports) {
        if (reports.isEmpty()) {
            return reports;
        }
        var statuses = new HashMap<UUID, List<UUID>>();
        jdbc.sql("""
                        SELECT report_id, status_id FROM report_status
                        WHERE report_id IN (:ids)
                        ORDER BY status_id
                        """)
                .param("ids", reports.stream().map(Report::id).toList())
                .query((rs, row) -> Map.entry(rs.getObject("report_id", UUID.class), rs.getObject("status_id", UUID.class)))
                .list()
                .forEach(entry -> statuses.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(entry.getValue()));
        return reports.stream()
                .map(report -> new Report(report.id(), report.reporterId(), report.targetId(), report.comment(),
                        report.category(), report.forwarded(), report.activityUri(), report.assignedTo(),
                        report.resolvedBy(), report.createdAt(), report.updatedAt(), report.resolvedAt(),
                        List.copyOf(statuses.getOrDefault(report.id(), List.of()))))
                .toList();
    }

    private static Report map(ResultSet rs, List<UUID> statusIds) throws SQLException {
        return new Report(
                rs.getObject("id", UUID.class),
                rs.getObject("reporter_id", UUID.class),
                rs.getObject("target_id", UUID.class),
                rs.getString("comment"),
                ReportCategory.fromValue(rs.getString("category")).orElse(ReportCategory.OTHER),
                rs.getBoolean("forwarded"),
                rs.getString("activity_uri"),
                rs.getObject("assigned_to", UUID.class),
                rs.getObject("resolved_by", UUID.class),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                instant(rs.getTimestamp("resolved_at")),
                statusIds);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
