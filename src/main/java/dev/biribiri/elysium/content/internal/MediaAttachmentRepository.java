package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.content.AttachmentType;
import dev.biribiri.elysium.content.Focus;
import dev.biribiri.elysium.content.MediaAttachment;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Every statement that removes a reference to a file adds the file's name to {@code media_file_deletion} in the same
 * statement, so a file is only ever removed once nothing refers to it.
 */
@Repository
public class MediaAttachmentRepository {

    private static final String COLUMNS = """
            id, account_id, status_id, type, file_name, preview_name, content_type, file_size, width, height,
            preview_width, preview_height, description, focus_x, focus_y, blurhash, remote_url, rejected, created_at""";

    /**
     * Turns the rows a statement returns into names to remove. It's appended to a CTE named {@code removed} that returns
     * {@code file_name} and {@code preview_name}.
     */
    private static final String QUEUE_REMOVED_FILES = """
            INSERT INTO media_file_deletion (name)
            SELECT name FROM removed, LATERAL (VALUES (removed.file_name), (removed.preview_name)) AS files (name)
            WHERE name IS NOT NULL
            ON CONFLICT DO NOTHING""";

    private static final int BATCH_SIZE = 1000;

    private static final RowMapper<MediaAttachment> MAPPER = MediaAttachmentRepository::map;

    private final JdbcClient jdbc;

    MediaAttachmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param removableAfter when it's removed if it hasn't been attached to a status
     */
    public void insert(MediaAttachment attachment, Instant removableAfter) {
        insert(attachment, removableAfter, 0);
    }

    /**
     * @param position where it comes among its status's attachments
     */
    public void insert(MediaAttachment attachment, Instant removableAfter, int position) {
        jdbc.sql("""
                        INSERT INTO media_attachment (id, account_id, status_id, position, type, file_name, preview_name,
                            content_type, file_size, width, height, preview_width, preview_height, description, focus_x,
                            focus_y, blurhash, remote_url, rejected, removable_after, created_at)
                        VALUES (:id, :accountId, :statusId, :position, :type, :fileName, :previewName, :contentType,
                            :fileSize, :width, :height, :previewWidth, :previewHeight, :description, :focusX, :focusY,
                            :blurhash, :remoteUrl, :rejected, :removableAfter, :createdAt)
                        """)
                .param("id", attachment.id())
                .param("position", position)
                .param("accountId", attachment.accountId())
                .param("statusId", attachment.statusId())
                .param("type", attachment.type().value())
                .param("fileName", attachment.fileName())
                .param("previewName", attachment.previewName())
                .param("contentType", attachment.contentType())
                .param("fileSize", attachment.fileSize())
                .param("width", attachment.width())
                .param("height", attachment.height())
                .param("previewWidth", attachment.previewWidth())
                .param("previewHeight", attachment.previewHeight())
                .param("description", attachment.description())
                .param("focusX", attachment.focus() == null ? null : attachment.focus().x())
                .param("focusY", attachment.focus() == null ? null : attachment.focus().y())
                .param("blurhash", attachment.blurhash())
                .param("remoteUrl", attachment.remoteUrl() == null ? null : attachment.remoteUrl().toString())
                .param("rejected", attachment.rejected())
                .param("removableAfter", timestamp(removableAfter))
                .param("createdAt", timestamp(attachment.createdAt()))
                .update();
    }

    /**
     * Uploads that can still be attached, which are those of this account's that aren't attached and haven't expired.
     */
    public int countAttachable(UUID accountId) {
        return jdbc.sql("""
                        SELECT count(*) FROM media_attachment
                        WHERE account_id = :accountId AND status_id IS NULL AND removable_after > now()
                        """)
                .param("accountId", accountId)
                .query(Integer.class)
                .single();
    }

    /**
     * Finds one of an account's uploads that can still be attached.
     */
    public Optional<MediaAttachment> findAttachable(UUID id, UUID accountId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM media_attachment
                        WHERE id = :id AND account_id = :accountId AND status_id IS NULL AND removable_after > now()
                        """)
                .param("id", id)
                .param("accountId", accountId)
                .query(MAPPER)
                .optional();
    }

    /**
     * Like {@link #findAttachable}, for several, and locked until the transaction ends, so that two statuses can't both
     * have one, and it can't be removed in between.
     */
    public List<MediaAttachment> findAttachableAndLock(Collection<UUID> ids, UUID accountId) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM media_attachment
                        WHERE id IN (:ids) AND account_id = :accountId AND status_id IS NULL AND removable_after > now()
                        FOR UPDATE
                        """)
                .param("ids", ids)
                .param("accountId", accountId)
                .query(MAPPER)
                .list();
    }

    public Optional<MediaAttachment> findOwn(UUID id, UUID accountId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM media_attachment WHERE id = :id AND account_id = :accountId")
                .param("id", id)
                .param("accountId", accountId)
                .query(MAPPER)
                .optional();
    }

    /**
     * @return a status's attachments, in order, locked until the transaction ends
     */
    public List<MediaAttachment> findByStatusAndLock(UUID statusId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM media_attachment WHERE status_id = :statusId ORDER BY position, id FOR UPDATE")
                .param("statusId", statusId)
                .query(MAPPER)
                .list();
    }

    /**
     * @return each status's attachments, in order, for each status that has any
     */
    public Map<UUID, List<MediaAttachment>> findByStatuses(Collection<UUID> statusIds) {
        if (statusIds.isEmpty()) {
            return Map.of();
        }
        var found = new HashMap<UUID, List<MediaAttachment>>();
        jdbc.sql("SELECT " + COLUMNS + " FROM media_attachment WHERE status_id IN (:statusIds) ORDER BY status_id, position, id")
                .param("statusIds", statusIds)
                .query(MAPPER)
                .list()
                .forEach(attachment -> found.computeIfAbsent(attachment.statusId(), id -> new ArrayList<>()).add(attachment));
        return found;
    }

    /**
     * Attaches uploads to a status, in the order given, which replaces the order of any it already has.
     *
     * @param ids every attachment the status is to have
     */
    public void attach(UUID statusId, UUID accountId, List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        UPDATE media_attachment
                        SET status_id = :statusId, removable_after = NULL,
                            position = array_position(CAST(:ids AS uuid[]), id) - 1
                        WHERE id = ANY (CAST(:ids AS uuid[])) AND account_id = :accountId
                            AND (status_id = :statusId OR status_id IS NULL)
                        """)
                .param("statusId", statusId)
                .param("accountId", accountId)
                .param("ids", ids.stream().map(UUID::toString).toArray(String[]::new))
                .update();
    }

    /**
     * Removes a status's attachments other than these, for an edit that took them out.
     */
    public void removeFromStatusExcept(UUID statusId, Collection<UUID> keep) {
        jdbc.sql("""
                        WITH removed AS (
                            DELETE FROM media_attachment
                            WHERE status_id = :statusId AND NOT (id = ANY (CAST(:keep AS uuid[])))
                            RETURNING file_name, preview_name
                        )
                        """ + QUEUE_REMOVED_FILES)
                .param("statusId", statusId)
                .param("keep", keep.stream().map(UUID::toString).toArray(String[]::new))
                .update();
    }

    /**
     * Keeps a status's attachments for a while once it's deleted, so that its author can post them again.
     */
    public void keepForRedraft(UUID statusId, Instant removableAfter) {
        jdbc.sql("UPDATE media_attachment SET removable_after = :removableAfter WHERE status_id = :statusId")
                .param("statusId", statusId)
                .param("removableAfter", timestamp(removableAfter))
                .update();
    }

    /**
     * Replaces what another server says about one of its attachments, which an edit can change, keeping any copy of it.
     */
    public void updateRemote(UUID id, int position, String description, Focus focus, String blurhash, Integer width,
                             Integer height) {
        jdbc.sql("""
                        UPDATE media_attachment
                        SET position = :position, description = :description, focus_x = :focusX, focus_y = :focusY,
                            blurhash = :blurhash, width = :width, height = :height
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("position", position)
                .param("description", description)
                .param("focusX", focus == null ? null : focus.x())
                .param("focusY", focus == null ? null : focus.y())
                .param("blurhash", blurhash)
                .param("width", width)
                .param("height", height)
                .update();
    }

    public void updateDescription(UUID id, String description, Focus focus) {
        jdbc.sql("""
                        UPDATE media_attachment SET description = :description, focus_x = :focusX, focus_y = :focusY
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("description", description)
                .param("focusX", focus == null ? null : focus.x())
                .param("focusY", focus == null ? null : focus.y())
                .update();
    }

    /**
     * @return whether it was removed, which it isn't if it's attached to a status
     */
    public boolean deleteUnattached(UUID id, UUID accountId) {
        return jdbc.sql("""
                        WITH removed AS (
                            DELETE FROM media_attachment
                            WHERE id = :id AND account_id = :accountId AND status_id IS NULL
                            RETURNING file_name, preview_name
                        ), queued AS (
                        """ + QUEUE_REMOVED_FILES + """
                        )
                        SELECT count(*) FROM removed
                        """)
                .param("id", id)
                .param("accountId", accountId)
                .query(Integer.class)
                .single() == 1;
    }

    /**
     * Removes the attachments of a status that's gone, or uploads that weren't attached in time. The conditions are
     * repeated outside the subquery, so that a row a status has attached since isn't removed.
     *
     * @return how many were removed, which is at most one batch
     */
    public int removeOrphans() {
        return jdbc.sql("""
                        WITH removed AS (
                            DELETE FROM media_attachment
                            WHERE id IN (
                                SELECT id FROM media_attachment
                                WHERE status_id IS NULL AND (removable_after IS NULL OR removable_after < now())
                                LIMIT :limit
                            ) AND status_id IS NULL AND (removable_after IS NULL OR removable_after < now())
                            RETURNING file_name, preview_name
                        ), queued AS (
                        """ + QUEUE_REMOVED_FILES + """
                        )
                        SELECT count(*) FROM removed
                        """)
                .param("limit", BATCH_SIZE)
                .query(Integer.class)
                .single();
    }

    /**
     * Removes these attachments if their status is gone, which a deletion has only just left them without, so that the
     * sweep for every orphan doesn't have to run for each one.
     */
    public void removeDetached(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        WITH removed AS (
                            DELETE FROM media_attachment
                            WHERE id = ANY (CAST(:ids AS uuid[])) AND status_id IS NULL
                            RETURNING file_name, preview_name
                        )
                        """ + QUEUE_REMOVED_FILES)
                .param("ids", ids.stream().map(UUID::toString).toArray(String[]::new))
                .update();
    }

    /**
     * Removes every attachment of an account's, attached or not, for when it's deleted.
     */
    public void removeAllBy(UUID accountId) {
        int removed;
        do {
            removed = jdbc.sql("""
                            WITH removed AS (
                                DELETE FROM media_attachment
                                WHERE id IN (SELECT id FROM media_attachment WHERE account_id = :accountId LIMIT :limit)
                                RETURNING file_name, preview_name
                            ), queued AS (
                            """ + QUEUE_REMOVED_FILES + """
                            )
                            SELECT count(*) FROM removed
                            """)
                    .param("accountId", accountId)
                    .param("limit", BATCH_SIZE)
                    .query(Integer.class)
                    .single();
        } while (removed == BATCH_SIZE);
    }

    /**
     * @return the names of files waiting to be removed, at most one batch
     */
    public List<String> findFilesToDelete() {
        return jdbc.sql("SELECT name FROM media_file_deletion ORDER BY queued_at LIMIT :limit")
                .param("limit", BATCH_SIZE)
                .query(String.class)
                .list();
    }

    public void forgetDeletedFiles(Collection<String> names) {
        if (names.isEmpty()) {
            return;
        }
        jdbc.sql("DELETE FROM media_file_deletion WHERE name IN (:names)").param("names", names).update();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static MediaAttachment map(ResultSet rs, int row) throws SQLException {
        var focusX = rs.getObject("focus_x", Double.class);
        var focusY = rs.getObject("focus_y", Double.class);
        var remoteUrl = rs.getString("remote_url");
        return new MediaAttachment(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getObject("status_id", UUID.class),
                AttachmentType.fromValue(rs.getString("type")),
                rs.getString("file_name"),
                rs.getString("preview_name"),
                rs.getString("content_type"),
                rs.getObject("file_size", Long.class),
                rs.getObject("width", Integer.class),
                rs.getObject("height", Integer.class),
                rs.getObject("preview_width", Integer.class),
                rs.getObject("preview_height", Integer.class),
                rs.getString("description"),
                focusX == null || focusY == null ? null : new Focus(focusX, focusY),
                rs.getString("blurhash"),
                remoteUrl == null ? null : URI.create(remoteUrl),
                rs.getBoolean("rejected"),
                rs.getTimestamp("created_at").toInstant());
    }
}
