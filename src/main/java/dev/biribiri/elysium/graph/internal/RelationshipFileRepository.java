package dev.biribiri.elysium.graph.internal;

import dev.biribiri.elysium.actor.ActorSql;
import dev.biribiri.elysium.graph.RelationshipKind;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Imports of follows, blocks, and mutes from CSV files, and what the exports of them read.
 */
@Repository
public class RelationshipFileRepository {

    private static final RowMapper<Row> ROW_MAPPER = (rs, rowNum) -> new Row(
            rs.getInt("position"),
            rs.getString("username"),
            rs.getString("domain"),
            nullableBoolean(rs, "show_reblogs"),
            nullableBoolean(rs, "notify"),
            strings(rs.getArray("languages")),
            nullableBoolean(rs, "hide_notifications"));

    private static final RowMapper<Progress> PROGRESS_MAPPER = (rs, rowNum) -> {
        var finishedAt = rs.getObject("finished_at", OffsetDateTime.class);
        return new Progress(
                rs.getObject("id", UUID.class),
                RelationshipKind.valueOf(rs.getString("kind").toUpperCase(Locale.ROOT)),
                rs.getInt("total"),
                rs.getInt("remaining"),
                rs.getInt("failed"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                finishedAt == null ? null : finishedAt.toInstant());
    };

    private final JdbcClient jdbc;

    RelationshipFileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Replaces the account's finished imports with a new one.
     *
     * @return false if the account already has one running, in which case nothing changed
     */
    public boolean insert(UUID importId, UUID accountId, RelationshipKind kind, List<Row> rows) {
        // DO NOTHING, since a unique violation would abort the whole transaction.
        var inserted = jdbc.sql("""
                        INSERT INTO relationship_import (id, account_id, kind, total)
                        VALUES (:id, :account, :kind, :total)
                        ON CONFLICT (account_id) WHERE finished_at IS NULL DO NOTHING
                        """)
                .param("id", importId)
                .param("account", accountId)
                .param("kind", kind.key())
                .param("total", rows.size())
                .update();
        if (inserted == 0) {
            return false;
        }
        jdbc.sql("DELETE FROM relationship_import WHERE account_id = :account AND finished_at IS NOT NULL")
                .param("account", accountId)
                .update();
        // One statement, since a file can list thousands. Languages go in as text, as an array can't hold arrays of
        // different lengths.
        jdbc.sql("""
                        INSERT INTO relationship_import_row
                            (import_id, position, username, domain, show_reblogs, notify, languages, hide_notifications)
                        SELECT :id, r.position, r.username, r.domain, r.show_reblogs, r.notify,
                            string_to_array(r.languages, ','), r.hide_notifications
                        FROM unnest(CAST(:positions AS integer[]), CAST(:usernames AS text[]), CAST(:domains AS text[]),
                            CAST(:showReblogs AS boolean[]), CAST(:notify AS boolean[]), CAST(:languages AS text[]),
                            CAST(:hideNotifications AS boolean[]))
                            AS r (position, username, domain, show_reblogs, notify, languages, hide_notifications)
                        """)
                .param("id", importId)
                .param("positions", rows.stream().map(Row::position).toArray(Integer[]::new))
                .param("usernames", rows.stream().map(Row::username).toArray(String[]::new))
                .param("domains", rows.stream().map(Row::domain).toArray(String[]::new))
                .param("showReblogs", rows.stream().map(Row::showReblogs).toArray(Boolean[]::new))
                .param("notify", rows.stream().map(Row::notifies).toArray(Boolean[]::new))
                .param("languages", rows.stream()
                        .map(row -> row.languages() == null ? null : String.join(",", row.languages()))
                        .toArray(String[]::new))
                .param("hideNotifications", rows.stream().map(Row::hideNotifications).toArray(Boolean[]::new))
                .update();
        return true;
    }

    /**
     * @return the account's import that's running, or else the last one to finish
     */
    public Optional<Progress> findLatest(UUID accountId) {
        return jdbc.sql("""
                        SELECT i.id, i.kind, i.total, i.failed, i.created_at, i.finished_at,
                            (SELECT count(*) FROM relationship_import_row r WHERE r.import_id = i.id) AS remaining
                        FROM relationship_import i
                        WHERE i.account_id = :account
                        ORDER BY i.finished_at IS NULL DESC, i.created_at DESC
                        LIMIT 1
                        """)
                .param("account", accountId)
                .query(PROGRESS_MAPPER)
                .optional();
    }

    /**
     * @return the import's account and kind, if it hasn't finished
     */
    public Optional<Running> findRunning(UUID importId) {
        return jdbc.sql("SELECT account_id, kind FROM relationship_import WHERE id = :id AND finished_at IS NULL")
                .param("id", importId)
                .query((rs, rowNum) -> new Running(
                        rs.getObject("account_id", UUID.class),
                        RelationshipKind.valueOf(rs.getString("kind").toUpperCase(Locale.ROOT))))
                .optional();
    }

    /**
     * @return the first rows still to be handled, in the file's order
     */
    public List<Row> findRows(UUID importId, int limit) {
        return jdbc.sql("""
                        SELECT position, username, domain, show_reblogs, notify, languages, hide_notifications
                        FROM relationship_import_row
                        WHERE import_id = :id
                        ORDER BY position
                        LIMIT :limit
                        """)
                .param("id", importId)
                .param("limit", limit)
                .query(ROW_MAPPER)
                .list();
    }

    /**
     * Removes a handled row, counting it as failed if it was. A row that's gone already isn't counted again.
     */
    public void deleteRow(UUID importId, int position, boolean failed) {
        var deleted = jdbc.sql("DELETE FROM relationship_import_row WHERE import_id = :id AND position = :position")
                .param("id", importId)
                .param("position", position)
                .update();
        if (deleted > 0 && failed) {
            jdbc.sql("UPDATE relationship_import SET failed = failed + 1 WHERE id = :id")
                    .param("id", importId)
                    .update();
        }
    }

    public void finish(UUID importId) {
        jdbc.sql("UPDATE relationship_import SET finished_at = now() WHERE id = :id AND finished_at IS NULL")
                .param("id", importId)
                .update();
    }

    public void delete(UUID importId) {
        jdbc.sql("DELETE FROM relationship_import WHERE id = :id").param("id", importId).update();
    }

    public void deleteForAccount(UUID accountId) {
        jdbc.sql("DELETE FROM relationship_import WHERE account_id = :account").param("account", accountId).update();
    }

    /**
     * Whether the actor is active and its server isn't suspended, which an instance block checks by the actor's own
     * domain, not the one in the address that found it.
     */
    public boolean isReachable(UUID actorId) {
        return jdbc.sql("SELECT 1 FROM actor a WHERE a.id = :id AND " + ActorSql.active("a") + " AND "
                        + ActorSql.serverNotSuspended("a"))
                .param("id", actorId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * The active accounts that an account follows and has been accepted by, oldest first, as Mastodon exports them.
     */
    public List<ExportedAccount> findFollowed(UUID accountId) {
        return findExported(accountId, "f.show_reblogs, f.notify, f.languages, NULL::boolean",
                "follow f JOIN actor a ON a.id = f.followed_id",
                "f.follower_id = :account AND f.state = 'accepted' AND " + ActorSql.active("a"), "f");
    }

    /**
     * Keeps suspended accounts, so that someone moving away still blocks the accounts moderators acted on here. Deleted
     * ones have nothing left to block.
     */
    public List<ExportedAccount> findBlocked(UUID accountId) {
        return findExported(accountId, "NULL::boolean, NULL::boolean, NULL::text[], NULL::boolean",
                "account_block r JOIN actor a ON a.id = r.target_id",
                "r.account_id = :account AND a.deleted_at IS NULL", "r");
    }

    /**
     * Leaves out expired mutes, which are ignored until they're purged. Keeps suspended accounts, as
     * {@link #findBlocked} does.
     */
    public List<ExportedAccount> findMuted(UUID accountId) {
        return findExported(accountId, "NULL::boolean, NULL::boolean, NULL::text[], r.notifications",
                "account_mute r JOIN actor a ON a.id = r.target_id",
                "r.account_id = :account AND (r.expires_at IS NULL OR r.expires_at > now()) AND a.deleted_at IS NULL",
                "r");
    }

    /**
     * @param options the follow options and a mute's notifications, in that order, as SQL
     * @param alias   the alias of the relationship's table in {@code from}, which has {@code created_at} and {@code id}
     */
    private List<ExportedAccount> findExported(UUID accountId, String options, String from, String where, String alias) {
        return jdbc.sql("SELECT a.username, a.domain, " + options + " FROM " + from + " WHERE " + where
                        + " ORDER BY " + alias + ".created_at, " + alias + ".id")
                .param("account", accountId)
                .query(ExportedAccount.MAPPER)
                .list();
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        return nullableBoolean(rs, rs.findColumn(column));
    }

    private static Boolean nullableBoolean(ResultSet rs, int column) throws SQLException {
        var value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? null : Arrays.asList((String[]) array.getArray());
    }

    /**
     * One account from an imported file. The options are null where the file didn't give them.
     *
     * @param domain            null for an address with no domain, which is a local account's
     * @param languages         a follow's languages, where an empty list means every language
     * @param hideNotifications whether a mute hides the account's notifications too
     */
    public record Row(
            int position,
            String username,
            String domain,
            Boolean showReblogs,
            Boolean notifies,
            List<String> languages,
            Boolean hideNotifications) {

        public Row {
            languages = languages == null ? null : List.copyOf(languages);
        }
    }

    public record Running(UUID accountId, RelationshipKind kind) {
    }

    /**
     * @param finishedAt null while rows are left
     */
    public record Progress(
            UUID id,
            RelationshipKind kind,
            int total,
            int remaining,
            int failed,
            Instant createdAt,
            Instant finishedAt) {
    }

    /**
     * An account to write to an export. Only the options that the kind of export has are given. A null languages, as a
     * follow stores every language, is written empty.
     */
    public record ExportedAccount(
            String username,
            String domain,
            Boolean showReblogs,
            Boolean notifies,
            List<String> languages,
            Boolean hideNotifications) {

        static final RowMapper<ExportedAccount> MAPPER = (rs, rowNum) -> new ExportedAccount(
                rs.getString(1),
                rs.getString(2),
                nullableBoolean(rs, 3),
                nullableBoolean(rs, 4),
                strings(rs.getArray(5)),
                nullableBoolean(rs, 6));
    }
}
