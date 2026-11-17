package dev.biribiri.elysium.actor.internal;

import dev.biribiri.elysium.media.StoredImage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProfileRepository {

    /**
     * @param avatar the copy kept here, or null if there isn't one
     * @param header the copy kept here, or null if there isn't one
     * @param remote null for a local account's profile
     * @param movedTo null if it hasn't moved
     */
    public record Row(
            UUID actorId,
            String displayName,
            String note,
            StoredImage avatar,
            StoredImage header,
            boolean manuallyApprovesFollowers,
            boolean indexable,
            Instant updatedAt,
            Remote remote,
            List<URI> alsoKnownAs,
            URI movedTo) {

        public Row {
            alsoKnownAs = List.copyOf(alsoKnownAs);
        }

        public Row(
                UUID actorId,
                String displayName,
                String note,
                StoredImage avatar,
                StoredImage header,
                boolean manuallyApprovesFollowers,
                boolean indexable,
                Instant updatedAt) {
            this(actorId, displayName, note, avatar, header, manuallyApprovesFollowers, indexable, updatedAt, null, List.of(),
                    null);
        }
    }

    /**
     * What only an account on another server's profile has. A profile is one of those when its {@code note_html} is set.
     *
     * @param avatarGivenUp whether the avatar is linked to rather than downloaded, until its URL changes
     * @param headerGivenUp likewise for the header
     */
    public record Remote(
            String noteHtml,
            URI avatarUrl,
            boolean avatarGivenUp,
            URI headerUrl,
            boolean headerGivenUp,
            URI url,
            boolean bot,
            boolean group,
            Instant published) {
    }

    /**
     * @param valueHtml null for a local account's field
     */
    public record FieldRow(UUID actorId, int position, String name, String value, Instant verifiedAt, String valueHtml) {

        public FieldRow(UUID actorId, int position, String name, String value, Instant verifiedAt) {
            this(actorId, position, name, value, verifiedAt, null);
        }
    }

    private static final String COLUMNS = """
            actor_id, display_name, note, avatar, avatar_static, header, header_static, manually_approves_followers,
            indexable, updated_at, note_html, avatar_remote_url, avatar_cache_given_up, header_remote_url, header_cache_given_up, url,
            bot, group_actor, published, also_known_as, moved_to_uri""";

    private final JdbcClient jdbc;

    ProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> find(Collection<UUID> actorIds) {
        if (actorIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM profile WHERE actor_id IN (:ids)")
                .param("ids", actorIds)
                .query(ProfileRepository::row)
                .list();
    }

    public List<FieldRow> findFields(Collection<UUID> actorIds) {
        if (actorIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT actor_id, position, name, value, verified_at, value_html FROM profile_field
                        WHERE actor_id IN (:ids) ORDER BY actor_id, position
                        """)
                .param("ids", actorIds)
                .query((rs, rowNum) -> new FieldRow(
                        rs.getObject("actor_id", UUID.class),
                        rs.getInt("position"),
                        rs.getString("name"),
                        rs.getString("value"),
                        instant(rs.getObject("verified_at", OffsetDateTime.class)),
                        rs.getString("value_html")))
                .list();
    }

    /**
     * Creates the profile if it doesn't exist, and locks it, so that two changes at once can't each keep the other's
     * images.
     */
    public Row lock(UUID actorId) {
        jdbc.sql("INSERT INTO profile (actor_id) VALUES (:actorId) ON CONFLICT DO NOTHING")
                .param("actorId", actorId)
                .update();
        return jdbc.sql("SELECT " + COLUMNS + " FROM profile WHERE actor_id = :actorId FOR UPDATE")
                .param("actorId", actorId)
                .query(ProfileRepository::row)
                .single();
    }

    /**
     * Reads whether an account approves its followers by hand, and holds that until the caller's transaction ends. A change
     * of it waits for the request being decided, and a request waits for the change, so none is left waiting after the
     * account stops approving them.
     */
    public boolean lockFollowerApproval(UUID actorId) {
        return jdbc.sql("SELECT manually_approves_followers FROM profile WHERE actor_id = :actorId FOR SHARE")
                .param("actorId", actorId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    /**
     * @return when it was saved, from the database's clock, as the other times on an account are
     */
    public Instant save(Row row) {
        return jdbc.sql("""
                        UPDATE profile SET display_name = :displayName, note = :note, avatar = :avatar,
                            avatar_static = :avatarStatic, header = :header, header_static = :headerStatic,
                            manually_approves_followers = :manuallyApprovesFollowers, indexable = :indexable, updated_at = clock_timestamp()
                        WHERE actor_id = :actorId
                        RETURNING updated_at
                        """)
                .param("actorId", row.actorId())
                .param("displayName", row.displayName())
                .param("note", row.note())
                .param("avatar", row.avatar() == null ? null : row.avatar().name())
                .param("avatarStatic", row.avatar() == null ? null : row.avatar().staticName())
                .param("header", row.header() == null ? null : row.header().name())
                .param("headerStatic", row.header() == null ? null : row.header().staticName())
                .param("manuallyApprovesFollowers", row.manuallyApprovesFollowers())
                .param("indexable", row.indexable())
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    public void replaceFields(UUID actorId, List<FieldRow> fields) {
        jdbc.sql("DELETE FROM profile_field WHERE actor_id = :actorId").param("actorId", actorId).update();
        for (var field : fields) {
            jdbc.sql("""
                            INSERT INTO profile_field (actor_id, position, name, value, verified_at, value_html)
                            VALUES (:actorId, :position, :name, :value, :verifiedAt, :valueHtml)
                            """)
                    .param("actorId", actorId)
                    .param("position", field.position())
                    .param("name", field.name())
                    .param("value", field.value())
                    .param("verifiedAt", time(field.verifiedAt()))
                    .param("valueHtml", field.valueHtml())
                    .update();
        }
    }

    /**
     * Saves everything about an account on another server's profile, which has to be locked.
     */
    public void saveRemote(Row row) {
        var remote = row.remote();
        jdbc.sql("""
                        UPDATE profile SET display_name = :displayName, note = :note, avatar = :avatar,
                            avatar_static = :avatarStatic, header = :header, header_static = :headerStatic,
                            manually_approves_followers = :manuallyApprovesFollowers, indexable = :indexable, note_html = :noteHtml,
                            avatar_remote_url = :avatarUrl, avatar_cache_given_up = :avatarGivenUp,
                            header_remote_url = :headerUrl, header_cache_given_up = :headerGivenUp, url = :url, bot = :bot,
                            group_actor = :group, published = :published, also_known_as = CAST(:alsoKnownAs AS text[]),
                            moved_to_uri = :movedTo, updated_at = clock_timestamp()
                        WHERE actor_id = :actorId
                        """)
                .param("actorId", row.actorId())
                .param("displayName", row.displayName())
                .param("note", row.note())
                .param("avatar", row.avatar() == null ? null : row.avatar().name())
                .param("avatarStatic", row.avatar() == null ? null : row.avatar().staticName())
                .param("header", row.header() == null ? null : row.header().name())
                .param("headerStatic", row.header() == null ? null : row.header().staticName())
                .param("manuallyApprovesFollowers", row.manuallyApprovesFollowers())
                .param("indexable", row.indexable())
                .param("noteHtml", remote.noteHtml())
                .param("avatarUrl", string(remote.avatarUrl()))
                .param("avatarGivenUp", remote.avatarGivenUp())
                .param("headerUrl", string(remote.headerUrl()))
                .param("headerGivenUp", remote.headerGivenUp())
                .param("url", string(remote.url()))
                .param("bot", remote.bot())
                .param("group", remote.group())
                .param("published", time(remote.published()))
                .param("alsoKnownAs", strings(row.alsoKnownAs()))
                .param("movedTo", string(row.movedTo()))
                .update();
    }

    /**
     * Saves which accounts a local account says are also it. The profile has to be locked.
     *
     * @return when it was saved, from the database's clock
     */
    public Instant saveAliases(UUID actorId, List<URI> alsoKnownAs) {
        return jdbc.sql("""
                        UPDATE profile SET also_known_as = CAST(:alsoKnownAs AS text[]), updated_at = clock_timestamp()
                        WHERE actor_id = :actorId
                        RETURNING updated_at
                        """)
                .param("actorId", actorId)
                .param("alsoKnownAs", strings(alsoKnownAs))
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    /**
     * Saves where a local account has moved to. The profile has to be locked.
     *
     * @param movedTo null when it's no longer moved
     * @return when it was saved, from the database's clock
     */
    public Instant saveMovedTo(UUID actorId, URI movedTo) {
        return jdbc.sql("""
                        UPDATE profile SET moved_to_uri = :movedTo, updated_at = clock_timestamp()
                        WHERE actor_id = :actorId
                        RETURNING updated_at
                        """)
                .param("actorId", actorId)
                .param("movedTo", string(movedTo))
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    /**
     * Marks an account on another server as moved, as a {@code Move} it sent says, until its actor document is fetched
     * again.
     */
    public void markRemoteMoved(UUID actorId, URI movedTo) {
        jdbc.sql("UPDATE profile SET moved_to_uri = :movedTo WHERE actor_id = :actorId AND note_html IS NOT NULL")
                .param("actorId", actorId)
                .param("movedTo", movedTo.toString())
                .update();
    }

    /**
     * @param header whether it's the header rather than the avatar
     * @return the URL of an image on another server that should be downloaded, if there is one
     */
    public Optional<URI> findImageToDownload(UUID actorId, boolean header) {
        var image = header ? "header" : "avatar";
        return jdbc.sql("""
                        SELECT %1$s_remote_url FROM profile
                        WHERE actor_id = :actorId AND note_html IS NOT NULL AND %1$s IS NULL
                          AND %1$s_remote_url IS NOT NULL AND NOT %1$s_cache_given_up
                        """.formatted(image))
                .param("actorId", actorId)
                .query(String.class)
                .optional()
                .map(URI::create);
    }

    /**
     * Only keeps a copy of the image the profile has now, which can have changed while it was downloaded.
     *
     * @return whether it was kept
     */
    public boolean cacheImage(UUID actorId, boolean header, URI url, StoredImage stored) {
        var image = header ? "header" : "avatar";
        return jdbc.sql("""
                        UPDATE profile SET %1$s = :name, %1$s_static = :staticName
                        WHERE actor_id = :actorId AND note_html IS NOT NULL AND %1$s IS NULL
                          AND %1$s_remote_url = :url AND NOT %1$s_cache_given_up
                        """.formatted(image))
                .param("actorId", actorId)
                .param("url", url.toString())
                .param("name", stored.name())
                .param("staticName", stored.staticName())
                .update() > 0;
    }

    /**
     * Links to the image from then on, until its URL changes.
     */
    public void giveUpCaching(UUID actorId, boolean header, URI url) {
        var image = header ? "header" : "avatar";
        jdbc.sql("""
                        UPDATE profile SET %1$s_cache_given_up = true
                        WHERE actor_id = :actorId AND note_html IS NOT NULL AND %1$s IS NULL AND %1$s_remote_url = :url
                        """.formatted(image))
                .param("actorId", actorId)
                .param("url", url.toString())
                .update();
    }

    /**
     * What dropping copies of images changed.
     *
     * @param profiles how many profiles were changed
     * @param images   the copies that were dropped, for deleting their files
     */
    public record Uncached(int profiles, List<StoredImage> images) {
    }

    /**
     * Drops the copies of an account on another server's images, and links to them from then on.
     */
    public Uncached uncacheImages(UUID actorId) {
        return uncacheImages("actor_id = :actorId", Map.of("actorId", actorId, "limit", 1));
    }

    /**
     * Like {@link #uncacheImages(UUID)}, for a batch of a server's accounts. Each one is only changed once, so repeating
     * this until it changes nothing ends.
     *
     * @param domain as actors have it
     */
    public Uncached uncacheImagesForDomain(String domain, int limit) {
        return uncacheImages("""
                        actor_id IN (SELECT id FROM actor WHERE domain = :domain AND NOT is_local)
                          AND (avatar IS NOT NULL OR header IS NOT NULL OR NOT avatar_cache_given_up
                               OR NOT header_cache_given_up)""",
                Map.of("domain", domain, "limit", limit));
    }

    private Uncached uncacheImages(String condition, Map<String, Object> params) {
        var rows = jdbc.sql("""
                        WITH old AS (
                            SELECT actor_id, avatar, avatar_static, header, header_static FROM profile
                            WHERE note_html IS NOT NULL AND %s
                            LIMIT :limit
                            FOR UPDATE
                        )
                        UPDATE profile p SET avatar = NULL, avatar_static = NULL, header = NULL, header_static = NULL,
                            avatar_cache_given_up = true, header_cache_given_up = true
                        FROM old WHERE p.actor_id = old.actor_id
                        RETURNING old.avatar, old.avatar_static, old.header, old.header_static
                        """.formatted(condition))
                .params(params)
                .query((rs, rowNum) -> new StoredImage[]{
                        image(rs.getString("avatar"), rs.getString("avatar_static")),
                        image(rs.getString("header"), rs.getString("header_static"))})
                .list();
        var images = rows.stream().flatMap(Arrays::stream).filter(Objects::nonNull).toList();
        return new Uncached(rows.size(), images);
    }

    /**
     * Lets an account on another server's images be downloaded again, the next time it's refreshed.
     */
    public void allowImages(UUID actorId) {
        jdbc.sql("""
                        UPDATE profile SET avatar_cache_given_up = false, header_cache_given_up = false
                        WHERE actor_id = :actorId AND note_html IS NOT NULL
                        """)
                .param("actorId", actorId)
                .update();
    }

    /**
     * Like {@link #allowImages(UUID)}, for a batch of a server's accounts, other than those whose own media is still
     * rejected.
     *
     * @param domain as actors have it
     * @return how many were changed
     */
    public int allowImagesForDomain(String domain, int limit) {
        return jdbc.sql("""
                        UPDATE profile SET avatar_cache_given_up = false, header_cache_given_up = false
                        WHERE actor_id IN (
                            SELECT p.actor_id FROM profile p JOIN actor a ON a.id = p.actor_id
                            WHERE a.domain = :domain AND NOT a.is_local AND a.media_rejected_at IS NULL
                              AND p.note_html IS NOT NULL AND (p.avatar_cache_given_up OR p.header_cache_given_up)
                            LIMIT :limit
                        )
                        """)
                .param("domain", domain)
                .param("limit", limit)
                .update();
    }

    /**
     * Deletes a batch of the profiles of accounts on another server, and their fields.
     *
     * @param domain as actors have it
     * @return what they had, for removing their images. It's empty once there are none left.
     */
    public List<Row> deleteBatchForDomain(String domain, int limit) {
        return jdbc.sql("""
                        DELETE FROM profile WHERE actor_id IN (
                            SELECT p.actor_id FROM profile p JOIN actor a ON a.id = p.actor_id
                            WHERE a.domain = :domain AND NOT a.is_local
                            LIMIT :limit
                        )
                        RETURNING\s""" + COLUMNS)
                .param("domain", domain)
                .param("limit", limit)
                .query(ProfileRepository::row)
                .list();
    }

    /**
     * Only marks a field whose value is still the one that was checked, since it can have changed while the page was
     * being fetched.
     *
     * @return whether it was marked
     */
    public boolean markVerified(UUID actorId, int position, String value, Instant at) {
        return jdbc.sql("""
                        UPDATE profile_field SET verified_at = :at
                        WHERE actor_id = :actorId AND position = :position AND value = :value AND verified_at IS NULL
                        """)
                .param("actorId", actorId)
                .param("position", position)
                .param("value", value)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .update() > 0;
    }

    /**
     * Deletes a profile and its fields.
     *
     * @return what it had, for removing its images
     */
    public Optional<Row> delete(UUID actorId) {
        return jdbc.sql("DELETE FROM profile WHERE actor_id = :actorId RETURNING " + COLUMNS)
                .param("actorId", actorId)
                .query(ProfileRepository::row)
                .optional();
    }

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        var noteHtml = rs.getString("note_html");
        var remote = noteHtml == null ? null : new Remote(
                noteHtml,
                uri(rs.getString("avatar_remote_url")),
                rs.getBoolean("avatar_cache_given_up"),
                uri(rs.getString("header_remote_url")),
                rs.getBoolean("header_cache_given_up"),
                uri(rs.getString("url")),
                rs.getBoolean("bot"),
                rs.getBoolean("group_actor"),
                instant(rs.getObject("published", OffsetDateTime.class)));
        return new Row(
                rs.getObject("actor_id", UUID.class),
                rs.getString("display_name"),
                rs.getString("note"),
                image(rs.getString("avatar"), rs.getString("avatar_static")),
                image(rs.getString("header"), rs.getString("header_static")),
                rs.getBoolean("manually_approves_followers"),
                rs.getBoolean("indexable"),
                instant(rs.getObject("updated_at", OffsetDateTime.class)),
                remote,
                uris((String[]) rs.getArray("also_known_as").getArray()),
                uri(rs.getString("moved_to_uri")));
    }

    private static List<URI> uris(String[] values) {
        return Arrays.stream(values).map(URI::create).toList();
    }

    private static String[] strings(List<URI> uris) {
        return uris.stream().map(URI::toString).toArray(String[]::new);
    }

    private static URI uri(String value) {
        return value == null ? null : URI.create(value);
    }

    private static String string(URI uri) {
        return uri == null ? null : uri.toString();
    }

    private static OffsetDateTime time(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static StoredImage image(String name, String staticName) {
        return name == null ? null : new StoredImage(name, staticName);
    }

    private static Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
