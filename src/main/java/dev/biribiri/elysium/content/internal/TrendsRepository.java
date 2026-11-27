package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.actor.ActorSql;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusSql;
import dev.biribiri.elysium.content.TagDay;
import dev.biribiri.elysium.content.Trends;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads current activity so edits, moderation, and removed interactions take effect on the next request. */
@Repository
public class TrendsRepository {

    private static final int HISTORY_DAYS = 7;
    private static final int RECENT_HOURS = 48;
    private static final String PUBLIC_ORIGINAL = "s.visibility = 'public' AND s.reblog_of_id IS NULL"
            + StatusSql.NOT_LOCAL_ONLY;
    /** Links in sensitive or spoilered posts don't count, nor do those posts themselves. */
    static final String WITHOUT_WARNING = PUBLIC_ORIGINAL + " AND NOT s.sensitive AND s.spoiler_text = ''";
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
        return shares("status_link", "url", WITHOUT_WARNING, viewer, limit, offset);
    }

    private List<Trends.Sharing> shares(
            String table, String column, String eligible, Optional<UUID> viewer, int limit, int offset) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var today = now.toLocalDate();
        var first = today.minusDays(HISTORY_DAYS - 1).atStartOfDay().atOffset(ZoneOffset.UTC);
        var usage = new LinkedHashMap<String, List<TagDay>>();
        // Ranking reads only the recent window, and the week of history is read only for the page that ranking chose,
        // under the same conditions, so a busy week costs no more than a busy two days. Grouping by account first lets
        // the ranking hash its rows, where count(DISTINCT) would sort them all, and spill to disk on a busy server.
        var sql = """
                WITH sharers AS (
                    SELECT t.%1$s AS item, s.actor_id, max(s.published_at) AS latest
                    %2$s JOIN %3$s t ON t.status_id = s.id
                    WHERE %4$s AND s.published_at >= :recent AND s.published_at <= :now %5$s
                    GROUP BY t.%1$s, s.actor_id
                ), ranked AS (
                    SELECT item, count(*) AS accounts, max(latest) AS latest
                    FROM sharers
                    GROUP BY item HAVING count(*) >= 2
                    ORDER BY accounts DESC, latest DESC, item
                    LIMIT :limit OFFSET :offset
                )
                SELECT r.item, used.day, used.uses, used.accounts
                FROM ranked r CROSS JOIN LATERAL (
                    SELECT (s.published_at AT TIME ZONE 'UTC')::date AS day,
                           count(*) AS uses, count(DISTINCT s.actor_id) AS accounts
                    %2$s JOIN %3$s t ON t.status_id = s.id
                    WHERE t.%1$s = r.item AND %4$s AND s.published_at >= :first AND s.published_at <= :now %5$s
                    GROUP BY day
                ) used
                ORDER BY r.accounts DESC, r.latest DESC, r.item, used.day DESC
                """.formatted(column, StatusSql.FROM_ACTIVE, table, eligible, AUTHOR_ELIGIBLE + visible(viewer));
        page(sql, viewer, limit, offset, now).param("first", first).query(rs -> {
            usage.computeIfAbsent(rs.getString("item"), ignored -> new ArrayList<>()).add(new TagDay(
                    rs.getObject("day", LocalDate.class), rs.getLong("uses"), rs.getLong("accounts")));
        });
        return usage.entrySet().stream()
                .map(entry -> new Trends.Sharing(entry.getKey(), TagDay.lastDays(today, HISTORY_DAYS, entry.getValue())))
                .toList();
    }

    public List<Status> statuses(Optional<UUID> viewer, int limit, int offset) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        // Starts from the recent likes and boosts, which are few, rather than from every post of the week, which mostly
        // have none.
        var sql = """
                WITH engagement AS (
                    SELECT l.status_id, l.actor_id FROM status_like l
                    WHERE l.activity_at BETWEEN :recent AND :now
                    UNION ALL
                    SELECT b.reblog_of_id, b.actor_id FROM status b
                    WHERE b.reblog_of_id IS NOT NULL AND b.visibility = 'public' AND NOT b.local_only
                    AND b.published_at BETWEEN :recent AND :now
                ), score AS (
                    SELECT e.status_id, count(DISTINCT a.id) AS accounts
                    FROM engagement e
                    JOIN status engaged ON engaged.id = e.status_id
                    JOIN actor a ON a.id = e.actor_id AND %s
                    WHERE a.id <> engaged.actor_id %s
                    GROUP BY e.status_id HAVING count(DISTINCT a.id) >= 2
                )
                SELECT s.id
                %s JOIN score ON score.status_id = s.id
                JOIN profile p ON p.actor_id = s.actor_id AND p.discoverable
                WHERE %s AND NOT s.reply
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
                """.formatted(LikeRepository.eligible("a"), LikeRepository.hiddenFrom(viewer, "a"),
                StatusSql.FROM_ACTIVE, WITHOUT_WARNING, AUTHOR_ELIGIBLE + visible(viewer),
                LikeRepository.eligible("qa"), LikeRepository.hiddenFrom(viewer, "qa"));
        return statuses.findByIdsInOrder(page(sql, viewer, limit, offset, now)
                .param("first", now.minusDays(HISTORY_DAYS)).query(UUID.class).list());
    }

    private JdbcClient.StatementSpec page(String sql, Optional<UUID> viewer, int limit, int offset, OffsetDateTime now) {
        var query = jdbc.sql(sql).param("limit", limit).param("offset", offset)
                .param("now", now).param("recent", now.minusHours(RECENT_HOURS));
        return viewer.isPresent() ? query.param("viewer", viewer.get()) : query;
    }

    private static String visible(Optional<UUID> viewer) {
        return viewer.isPresent() ? StatusSql.notHidden(":viewer") : "";
    }
}
