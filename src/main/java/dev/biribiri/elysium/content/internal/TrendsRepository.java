package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.actor.ActorSql;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusSql;
import dev.biribiri.elysium.content.Trends;
import dev.biribiri.elysium.graph.HiddenAccounts;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

/** Reads current activity so edits, moderation, and removed interactions take effect on the next request. */
@Repository
public class TrendsRepository {

    private static final int HISTORY_DAYS = 7;
    private static final int RECENT_HOURS = 48;
    private static final String PUBLIC_ORIGINAL = "s.visibility = 'public' AND s.reblog_of_id IS NULL"
            + StatusSql.NOT_LOCAL_ONLY;
    private static final String AUTHOR_ELIGIBLE = " AND " + ActorSql.serverNotSuspended("author");

    private final JdbcClient jdbc;
    private final StatusRepository statuses;

    TrendsRepository(JdbcClient jdbc, StatusRepository statuses) {
        this.jdbc = jdbc;
        this.statuses = statuses;
    }

    public List<Trends.Sharing> tags(Optional<UUID> viewer, int limit, int offset) {
        return shares("status_tag", "name", PUBLIC_ORIGINAL, viewer, limit, offset);
    }

    public List<Trends.Sharing> links(Optional<UUID> viewer, int limit, int offset) {
        return shares("status_link", "url", StatusLinks.ELIGIBLE, viewer, limit, offset);
    }

    private List<Trends.Sharing> shares(
            String table, String column, String eligible, Optional<UUID> viewer, int limit, int offset) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var today = now.toLocalDate();
        var first = today.minusDays(HISTORY_DAYS - 1).atStartOfDay().atOffset(ZoneOffset.UTC);
        var usage = new LinkedHashMap<String, Map<LocalDate, Trends.Day>>();
        // Choose the ranked page before building history, using the same eligible shares for both.
        var sql = """
                WITH shares AS (
                    SELECT t.%s AS item, s.actor_id, s.published_at
                    %s JOIN %s t ON t.status_id = s.id
                    WHERE %s AND s.published_at >= :first AND s.published_at <= :now %s
                ), ranked AS (
                    SELECT item, count(DISTINCT actor_id) AS accounts, max(published_at) AS latest
                    FROM shares WHERE published_at >= :recent
                    GROUP BY item HAVING count(DISTINCT actor_id) >= 2
                    ORDER BY accounts DESC, latest DESC, item
                    LIMIT :limit OFFSET :offset
                )
                SELECT r.item, (s.published_at AT TIME ZONE 'UTC')::date AS day,
                       count(*) AS uses, count(DISTINCT s.actor_id) AS accounts
                FROM ranked r JOIN shares s USING (item)
                GROUP BY r.item, r.accounts, r.latest, day
                ORDER BY r.accounts DESC, r.latest DESC, r.item, day DESC
                """.formatted(column, StatusSql.FROM_ACTIVE, table, eligible, AUTHOR_ELIGIBLE + visible(viewer));
        page(sql, viewer, limit, offset, now).param("first", first).query(rs -> {
            var day = rs.getObject("day", LocalDate.class);
            usage.computeIfAbsent(rs.getString("item"), ignored -> new HashMap<>())
                    .put(day, new Trends.Day(day, rs.getLong("uses"), rs.getLong("accounts")));
        });
        return usage.entrySet().stream().map(entry -> new Trends.Sharing(entry.getKey(), IntStream.range(0, HISTORY_DAYS)
                .mapToObj(daysAgo -> today.minusDays(daysAgo))
                .map(day -> entry.getValue().getOrDefault(day, new Trends.Day(day, 0, 0)))
                .toList())).toList();
    }

    public List<Status> statuses(Optional<UUID> viewer, int limit, int offset) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var sql = """
                SELECT s.id
                %s JOIN profile p ON p.actor_id = s.actor_id AND p.discoverable
                JOIN LATERAL (
                    SELECT count(DISTINCT a.id) AS accounts
                    FROM (
                        SELECT l.actor_id FROM status_like l
                        WHERE l.status_id = s.id AND l.activity_at BETWEEN :recent AND :now
                        UNION ALL
                        SELECT b.actor_id FROM status b
                        WHERE b.reblog_of_id = s.id AND b.visibility = 'public' AND NOT b.local_only
                        AND b.published_at BETWEEN :recent AND :now
                    ) engagement
                    JOIN actor a ON a.id = engagement.actor_id AND %s
                    WHERE a.id <> s.actor_id %s
                ) score ON score.accounts >= 2
                WHERE %s AND NOT s.reply AND NOT s.sensitive AND s.spoiler_text = ''
                AND s.published_at BETWEEN :first AND :now %s
                AND NOT EXISTS (
                    SELECT 1 FROM status_quote q WHERE q.status_id = s.id
                    AND NOT EXISTS (
                        SELECT 1 FROM status qs
                        JOIN actor qa ON qa.id = qs.actor_id AND %s
                        JOIN profile qp ON qp.actor_id = qa.id AND qp.discoverable
                        WHERE q.state = 'accepted' AND qs.id = q.quoted_status_id AND qs.visibility = 'public'
                        AND NOT qs.local_only AND NOT qs.sensitive AND qs.spoiler_text = '' %s
                    )
                )
                ORDER BY score.accounts DESC, s.published_at DESC, s.id DESC
                LIMIT :limit OFFSET :offset
                """.formatted(StatusSql.FROM_ACTIVE, LikeRepository.eligible("a"), visibleActor(viewer, "a"),
                PUBLIC_ORIGINAL, AUTHOR_ELIGIBLE + visible(viewer), LikeRepository.eligible("qa"),
                visibleActor(viewer, "qa"));
        var ids = page(sql, viewer, limit, offset, now).param("first", now.minusDays(HISTORY_DAYS))
                .query(UUID.class).list();
        var rank = new HashMap<UUID, Integer>();
        for (var i = 0; i < ids.size(); i++) {
            rank.put(ids.get(i), i);
        }
        return statuses.findByIds(ids).stream().sorted(Comparator.comparingInt(status -> rank.get(status.id()))).toList();
    }

    private JdbcClient.StatementSpec page(String sql, Optional<UUID> viewer, int limit, int offset, OffsetDateTime now) {
        var query = jdbc.sql(sql).param("limit", limit).param("offset", offset)
                .param("now", now).param("recent", now.minusHours(RECENT_HOURS));
        return viewer.isPresent() ? query.param("viewer", viewer.get()) : query;
    }

    private static String visible(Optional<UUID> viewer) {
        return viewer.isPresent() ? StatusSql.notHidden(":viewer") : "";
    }

    private static String visibleActor(Optional<UUID> viewer, String actor) {
        return viewer.isPresent() ? " AND " + HiddenAccounts.visibleInTimelines(actor) : "";
    }
}
