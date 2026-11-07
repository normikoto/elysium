package dev.biribiri.elysium.actor.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProfileRepository {

    /**
     * Image columns are file names in the media directory.
     */
    public record Row(
            UUID actorId,
            String displayName,
            String note,
            String avatar,
            String avatarStatic,
            String header,
            String headerStatic,
            Instant updatedAt) {
    }

    public record FieldRow(UUID actorId, int position, String name, String value, Instant verifiedAt) {
    }

    private final JdbcClient jdbc;

    ProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> find(Collection<UUID> actorIds) {
        if (actorIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT actor_id, display_name, note, avatar, avatar_static, header, header_static, updated_at
                        FROM profile WHERE actor_id IN (:ids)
                        """)
                .param("ids", actorIds)
                .query(ProfileRepository::row)
                .list();
    }

    public List<FieldRow> findFields(Collection<UUID> actorIds) {
        if (actorIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT actor_id, position, name, value, verified_at FROM profile_field
                        WHERE actor_id IN (:ids) ORDER BY actor_id, position
                        """)
                .param("ids", actorIds)
                .query((rs, rowNum) -> new FieldRow(
                        rs.getObject("actor_id", UUID.class),
                        rs.getInt("position"),
                        rs.getString("name"),
                        rs.getString("value"),
                        instant(rs.getObject("verified_at", OffsetDateTime.class))))
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
        return jdbc.sql("""
                        SELECT actor_id, display_name, note, avatar, avatar_static, header, header_static, updated_at
                        FROM profile WHERE actor_id = :actorId FOR UPDATE
                        """)
                .param("actorId", actorId)
                .query(ProfileRepository::row)
                .single();
    }

    /**
     * @return when it was saved, from the database's clock, as the other times on an account are
     */
    public Instant save(Row row) {
        return jdbc.sql("""
                        UPDATE profile SET display_name = :displayName, note = :note, avatar = :avatar,
                            avatar_static = :avatarStatic, header = :header, header_static = :headerStatic,
                            updated_at = clock_timestamp()
                        WHERE actor_id = :actorId
                        RETURNING updated_at
                        """)
                .param("actorId", row.actorId())
                .param("displayName", row.displayName())
                .param("note", row.note())
                .param("avatar", row.avatar())
                .param("avatarStatic", row.avatarStatic())
                .param("header", row.header())
                .param("headerStatic", row.headerStatic())
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    public void replaceFields(UUID actorId, List<FieldRow> fields) {
        jdbc.sql("DELETE FROM profile_field WHERE actor_id = :actorId").param("actorId", actorId).update();
        for (var field : fields) {
            jdbc.sql("""
                            INSERT INTO profile_field (actor_id, position, name, value, verified_at)
                            VALUES (:actorId, :position, :name, :value, :verifiedAt)
                            """)
                    .param("actorId", actorId)
                    .param("position", field.position())
                    .param("name", field.name())
                    .param("value", field.value())
                    .param("verifiedAt", field.verifiedAt() == null ? null : OffsetDateTime.ofInstant(field.verifiedAt(), ZoneOffset.UTC))
                    .update();
        }
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
        return jdbc.sql("""
                        DELETE FROM profile WHERE actor_id = :actorId
                        RETURNING actor_id, display_name, note, avatar, avatar_static, header, header_static, updated_at
                        """)
                .param("actorId", actorId)
                .query(ProfileRepository::row)
                .optional();
    }

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                rs.getObject("actor_id", UUID.class),
                rs.getString("display_name"),
                rs.getString("note"),
                rs.getString("avatar"),
                rs.getString("avatar_static"),
                rs.getString("header"),
                rs.getString("header_static"),
                instant(rs.getObject("updated_at", OffsetDateTime.class)));
    }

    private static Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
