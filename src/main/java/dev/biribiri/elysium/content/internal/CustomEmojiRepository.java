package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.content.CustomEmoji;
import dev.biribiri.elysium.content.RemoteEmojiSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Like {@link MediaAttachmentRepository}, every statement that removes a reference to an emoji's files adds them to
 * {@code media_file_deletion} in the same statement.
 */
@Repository
public class CustomEmojiRepository {

    private static final String COLUMNS = """
            e.id, e.shortcode, e.domain, e.uri, e.image_name, e.static_name, e.remote_url, e.category,
            e.visible_in_picker""";

    /**
     * Appended to a CTE named {@code removed} that returns {@code image_name} and {@code static_name}.
     */
    private static final String QUEUE_REMOVED_FILES = """
            INSERT INTO media_file_deletion (name)
            SELECT name FROM removed, LATERAL (VALUES (removed.image_name), (removed.static_name)) AS files (name)
            WHERE name IS NOT NULL
            ON CONFLICT DO NOTHING""";

    private static final String ON_DOMAIN = DomainSql.covers(":domain", "e.domain");

    private static final int BATCH_SIZE = 1000;

    private static final RowMapper<CustomEmoji> MAPPER = CustomEmojiRepository::map;

    private final JdbcClient jdbc;

    CustomEmojiRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return false if this server already has an emoji with the shortcode
     */
    public boolean insertLocal(CustomEmoji emoji) {
        return jdbc.sql("""
                        INSERT INTO custom_emoji (id, shortcode, uri, image_name, static_name, category, visible_in_picker)
                        VALUES (:id, :shortcode, :uri, :imageName, :staticName, :category, :visibleInPicker)
                        ON CONFLICT DO NOTHING
                        """)
                .param("id", emoji.id())
                .param("shortcode", emoji.shortcode())
                .param("uri", emoji.uri().toString())
                .param("imageName", emoji.imageName())
                .param("staticName", emoji.staticName())
                .param("category", emoji.category(), Types.VARCHAR)
                .param("visibleInPicker", emoji.visibleInPicker())
                .update() == 1;
    }

    public List<CustomEmoji> findAllLocal() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM custom_emoji e WHERE e.domain IS NULL ORDER BY e.shortcode")
                .query(MAPPER)
                .list();
    }

    public List<CustomEmoji> findLocal(Collection<String> shortcodes) {
        if (shortcodes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM custom_emoji e WHERE e.domain IS NULL AND e.shortcode IN (:shortcodes)")
                .param("shortcodes", shortcodes)
                .query(MAPPER)
                .list();
    }

    public Optional<CustomEmoji> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM custom_emoji e WHERE e.id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public List<CustomEmoji> findByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM custom_emoji e WHERE e.id IN (:ids)")
                .param("ids", ids)
                .query(MAPPER)
                .list();
    }

    public Optional<CustomEmoji> findRemote(String shortcode, String domain) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM custom_emoji e WHERE e.shortcode = :shortcode AND e.domain = :domain")
                .param("shortcode", shortcode)
                .param("domain", domain)
                .query(MAPPER)
                .optional();
    }

    public boolean updateLocal(UUID id, String category, boolean visibleInPicker) {
        return jdbc.sql("""
                        UPDATE custom_emoji SET category = :category, visible_in_picker = :visibleInPicker, updated_at = now()
                        WHERE id = :id AND domain IS NULL
                        """)
                .param("id", id)
                .param("category", category, Types.VARCHAR)
                .param("visibleInPicker", visibleInPicker)
                .update() == 1;
    }

    /**
     * @return whether it was removed, whose files are then queued for removal
     */
    public boolean deleteLocal(UUID id) {
        return jdbc.sql("""
                        WITH removed AS (
                            DELETE FROM custom_emoji WHERE id = :id AND domain IS NULL RETURNING image_name, static_name
                        ), queued AS (
                        """ + QUEUE_REMOVED_FILES + """
                        )
                        SELECT count(*) FROM removed
                        """)
                .param("id", id)
                .query(Integer.class)
                .single() > 0;
    }

    /**
     * What storing another server's emoji did.
     *
     * @param needsDownload whether it's new, its image changed, or it hasn't been downloaded and could be, so that a
     *                      download is queued, which does nothing if one already is
     */
    public record Stored(UUID id, boolean needsDownload) {
    }

    /**
     * Stores another server's emoji, or marks it as used if it's stored already. Its row is held until the transaction
     * ends, so that it can't be removed as unused before what uses it is stored. {@code seen_at} is only rewritten when it's
     * an hour old, since an emoji that's used often is used by many statuses at once, and each write would make the next
     * one wait. A different image replaces the one kept, unless its server says it's older, as an old status that's
     * fetched again can name an old image.
     */
    public Stored upsertRemote(UUID newId, String shortcode, String domain, URI uri, URI url, Instant updated) {
        var inserted = jdbc.sql("""
                        INSERT INTO custom_emoji (id, shortcode, domain, uri, remote_url, remote_updated)
                        VALUES (:id, :shortcode, :domain, :uri, :url, :updated)
                        ON CONFLICT (shortcode, domain) DO NOTHING
                        """)
                .param("id", newId)
                .param("shortcode", shortcode)
                .param("domain", domain)
                .param("uri", uri.toString())
                .param("url", url.toString())
                .param("updated", updated == null ? null : updated.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .update() == 1;
        if (inserted) {
            return new Stored(newId, true);
        }
        // Concurrently removed as unused, which leaves nothing to lock, so the caller's job runs again.
        var row = jdbc.sql("""
                        SELECT id, remote_url, remote_updated, image_name, cache_given_up FROM custom_emoji
                        WHERE shortcode = :shortcode AND domain = :domain
                        FOR KEY SHARE
                        """)
                .param("shortcode", shortcode)
                .param("domain", domain)
                .query((rs, rowNum) -> new UpsertRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("remote_url"),
                        instant(rs, "remote_updated"),
                        rs.getString("image_name") == null && !rs.getBoolean("cache_given_up")))
                .single();
        jdbc.sql("UPDATE custom_emoji SET seen_at = now() WHERE id = :id AND seen_at < now() - interval '1 hour'")
                .param("id", row.id())
                .update();
        if (updated != null && row.updated() != null && updated.isBefore(row.updated())) {
            return new Stored(row.id(), row.uncached());
        }
        var newer = updated != null && (row.updated() == null || updated.isAfter(row.updated()));
        if (!newer && url.toString().equals(row.url())) {
            return new Stored(row.id(), row.uncached());
        }
        jdbc.sql("""
                        WITH old AS (
                            SELECT id, image_name, static_name FROM custom_emoji WHERE id = :id
                        ), changed AS (
                            UPDATE custom_emoji e
                            SET uri = :uri, remote_url = :url, remote_updated = coalesce(:updated, e.remote_updated),
                                image_name = NULL, static_name = NULL, cache_given_up = false, updated_at = now()
                            FROM old WHERE e.id = old.id
                            RETURNING old.image_name, old.static_name
                        ), removed AS (
                            SELECT image_name, static_name FROM changed
                        )
                        """ + QUEUE_REMOVED_FILES)
                .param("id", row.id())
                .param("uri", uri.toString())
                .param("url", url.toString())
                .param("updated", updated == null ? null : updated.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return new Stored(row.id(), true);
    }

    /**
     * @param uncached whether it has no copy, and one can still be downloaded
     */
    private record UpsertRow(UUID id, String url, Instant updated, boolean uncached) {
    }

    public Optional<RemoteEmojiSource> findForDownload(UUID id) {
        return jdbc.sql("""
                        SELECT id, remote_url, domain FROM custom_emoji
                        WHERE id = :id AND domain IS NOT NULL AND image_name IS NULL AND NOT cache_given_up
                        """)
                .param("id", id)
                .query((rs, row) -> new RemoteEmojiSource(
                        rs.getObject("id", UUID.class), URI.create(rs.getString("remote_url")), rs.getString("domain")))
                .optional();
    }

    /**
     * Records a downloaded image, unless the emoji's been removed, downloaded, or given another image since.
     *
     * @return whether it was recorded. If not, the caller removes the files
     */
    public boolean cache(UUID id, URI url, String imageName, String staticName) {
        return jdbc.sql("""
                        UPDATE custom_emoji SET image_name = :imageName, static_name = :staticName
                        WHERE id = :id AND domain IS NOT NULL AND image_name IS NULL AND NOT cache_given_up
                            AND remote_url = :url
                        """)
                .param("id", id)
                .param("url", url.toString())
                .param("imageName", imageName)
                .param("staticName", staticName)
                .update() == 1;
    }

    public void giveUpCaching(UUID id) {
        jdbc.sql("UPDATE custom_emoji SET cache_given_up = true WHERE id = :id AND image_name IS NULL")
                .param("id", id)
                .update();
    }

    /**
     * Removes one batch of a server's emoji, for when it's blocked.
     *
     * @param domain a domain as domain blocks have it, which covers its subdomains
     * @return how many were removed
     */
    public int removeForDomain(String domain) {
        return jdbc.sql("""
                        WITH removed AS (
                            DELETE FROM custom_emoji WHERE id IN (
                                SELECT e.id FROM custom_emoji e WHERE e.domain IS NOT NULL AND %s LIMIT :limit
                            )
                            RETURNING image_name, static_name
                        ), queued AS (
                        """.formatted(ON_DOMAIN) + QUEUE_REMOVED_FILES + """
                        )
                        SELECT count(*) FROM removed
                        """)
                .param("domain", domain)
                .param("limit", BATCH_SIZE)
                .query(Integer.class)
                .single();
    }

    /**
     * Removes the copies of one batch of a server's emoji, and stops them being downloaded again, for when an admin
     * starts rejecting its media.
     *
     * @return how many were changed
     */
    public int rejectForDomain(String domain) {
        return jdbc.sql("""
                        WITH old AS (
                            SELECT e.id, e.image_name, e.static_name FROM custom_emoji e
                            WHERE e.domain IS NOT NULL AND (e.image_name IS NOT NULL OR NOT e.cache_given_up) AND %s
                            LIMIT :limit
                            FOR UPDATE OF e
                        ), removed AS (
                            UPDATE custom_emoji e SET image_name = NULL, static_name = NULL, cache_given_up = true
                            FROM old WHERE e.id = old.id
                            RETURNING old.image_name, old.static_name
                        ), queued AS (
                        """.formatted(ON_DOMAIN) + QUEUE_REMOVED_FILES + """
                        )
                        SELECT count(*) FROM removed
                        """)
                .param("domain", domain)
                .param("limit", BATCH_SIZE)
                .query(Integer.class)
                .single();
    }

    /**
     * Lets one batch of a server's emoji be downloaded again, for when an admin stops rejecting its media, unless another
     * block still rejects it. Each is downloaded the next time something uses it.
     *
     * @return how many were changed
     */
    public int allowForDomain(String domain) {
        return jdbc.sql("""
                        UPDATE custom_emoji SET cache_given_up = false
                        WHERE id IN (
                            SELECT e.id FROM custom_emoji e
                            WHERE e.domain IS NOT NULL AND e.cache_given_up AND e.image_name IS NULL AND %s AND NOT %s
                            LIMIT :limit
                        )
                        """.formatted(ON_DOMAIN, DomainSql.mediaRejected("e.domain")))
                .param("domain", domain)
                .param("limit", BATCH_SIZE)
                .update();
    }

    /**
     * Removes one batch of other servers' emoji that no status has used for a while. Marking an emoji as used rewrites a
     * {@code seen_at} that's old enough for this to take, which PostgreSQL checks again on a row that changed while this
     * waited for it, so an emoji that's being stored as used isn't removed.
     *
     * @return how many were removed
     */
    public int removeUnused(Instant seenBefore) {
        return jdbc.sql("""
                        WITH removed AS (
                            DELETE FROM custom_emoji WHERE id IN (
                                SELECT e.id FROM custom_emoji e
                                WHERE e.domain IS NOT NULL AND e.seen_at < :seenBefore
                                  AND NOT EXISTS (SELECT 1 FROM status_emoji s WHERE s.emoji_id = e.id)
                                LIMIT :limit
                            ) AND seen_at < :seenBefore
                            RETURNING image_name, static_name
                        ), queued AS (
                        """ + QUEUE_REMOVED_FILES + """
                        )
                        SELECT count(*) FROM removed
                        """)
                .param("seenBefore", seenBefore.atOffset(ZoneOffset.UTC))
                .param("limit", BATCH_SIZE)
                .query(Integer.class)
                .single();
    }

    /**
     * Emoji that have been removed meanwhile are left out, rather than failing what uses them: each is held while it's
     * linked, which waits for a removal that's under way.
     */
    public void insertForStatus(UUID statusId, Collection<UUID> emojiIds) {
        if (emojiIds.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        WITH held AS (
                            SELECT id FROM custom_emoji WHERE id = ANY (CAST(:ids AS uuid[])) ORDER BY id FOR KEY SHARE
                        )
                        INSERT INTO status_emoji (status_id, emoji_id)
                        SELECT :statusId, id FROM held
                        ON CONFLICT DO NOTHING
                        """)
                .param("statusId", statusId)
                .param("ids", emojiIds.toArray(UUID[]::new))
                .update();
    }

    public void replaceForStatus(UUID statusId, Collection<UUID> emojiIds) {
        jdbc.sql("DELETE FROM status_emoji WHERE status_id = :statusId AND NOT (emoji_id = ANY (CAST(:ids AS uuid[])))")
                .param("statusId", statusId)
                .param("ids", emojiIds.toArray(UUID[]::new))
                .update();
        insertForStatus(statusId, emojiIds);
    }

    /**
     * @return each status's emoji, by shortcode, for each status that has any
     */
    public Map<UUID, List<CustomEmoji>> findByStatuses(Collection<UUID> statusIds) {
        if (statusIds.isEmpty()) {
            return Map.of();
        }
        var emojis = new HashMap<UUID, List<CustomEmoji>>();
        jdbc.sql("SELECT s.status_id, " + COLUMNS + """
                         FROM status_emoji s JOIN custom_emoji e ON e.id = s.emoji_id
                        WHERE s.status_id IN (:statusIds) ORDER BY s.status_id, e.shortcode
                        """)
                .param("statusIds", statusIds)
                .query(rs -> {
                    emojis.computeIfAbsent(rs.getObject("status_id", UUID.class), id -> new ArrayList<>())
                            .add(map(rs, 0));
                });
        return emojis;
    }

    private static CustomEmoji map(ResultSet rs, int row) throws SQLException {
        var remoteUrl = rs.getString("remote_url");
        return new CustomEmoji(
                rs.getObject("id", UUID.class),
                rs.getString("shortcode"),
                rs.getString("domain"),
                URI.create(rs.getString("uri")),
                rs.getString("image_name"),
                rs.getString("static_name"),
                remoteUrl == null ? null : URI.create(remoteUrl),
                rs.getString("category"),
                rs.getBoolean("visible_in_picker"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, java.time.OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
