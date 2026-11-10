package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.content.AccountStats;
import dev.biribiri.elysium.content.DeletedStatus;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusOrigin;
import dev.biribiri.elysium.content.StatusPage;
import dev.biribiri.elysium.content.StatusVersion;
import dev.biribiri.elysium.content.TagDay;
import dev.biribiri.elysium.content.TagFilter;
import dev.biribiri.elysium.content.Visibility;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SimplePropertyRowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class StatusRepository {

    private static final String COLUMNS = """
            s.id, s.uri, s.actor_id, s.text, s.content, s.spoiler_text, s.language, s.visibility, s.sensitive, s.reply,
            s.in_reply_to_id, s.in_reply_to_account_id, s.in_reply_to_uri, s.url, s.published_at, s.edited_at, s.reblog_of_id""";

    private static final String COUNT_REPLIES = """
            SELECT count(*) FROM status r
            JOIN actor replier ON replier.id = r.actor_id AND replier.suspended_at IS NULL AND replier.deleted_at IS NULL
            WHERE r.in_reply_to_id = %s AND r.visibility IN ('public', 'unlisted')""";

    /**
     * Only finds statuses of accounts that are neither suspended nor deleted, so that those of a suspended account aren't
     * served by anything, and aren't left to be filtered out after a page of them has been chosen. The same goes for boosts
     * of their statuses. Counts use this too, so that they agree with what's listed.
     */
    private static final String FROM_ACTIVE = "FROM status s "
            + "JOIN actor author ON author.id = s.actor_id AND author.suspended_at IS NULL AND author.deleted_at IS NULL "
            + "AND (s.reblog_of_id IS NULL OR EXISTS (SELECT 1 FROM status o JOIN actor oa ON oa.id = o.actor_id "
            + "WHERE o.id = s.reblog_of_id AND oa.suspended_at IS NULL AND oa.deleted_at IS NULL)) ";

    private static final String SELECT = "SELECT " + COLUMNS + ", (" + COUNT_REPLIES.formatted("s.id")
            + ") AS replies_count " + FROM_ACTIVE;

    private static final int REMOVAL_BATCH_SIZE = 1000;

    /**
     * How many versions of a status are kept, counting the current one.
     */
    public static final int MAX_VERSIONS = 50;

    private static final String HAS_MEDIA =
            " AND EXISTS (SELECT 1 FROM media_attachment m WHERE m.status_id = s.id)";

    private static final String MENTIONS_VIEWER =
            " EXISTS (SELECT 1 FROM status_mention m WHERE m.status_id = s.id AND m.actor_id = :viewer)";

    /**
     * Excludes replies to other accounts, but keeps an account's replies to itself.
     */
    private static final String NOT_A_REPLY_TO_OTHERS = "(NOT s.reply OR s.in_reply_to_account_id = s.actor_id)";

    /**
     * Identifies the lock on assigning status IDs. Any value works, as long as nothing else uses it as an advisory lock.
     */
    private static final long ID_ASSIGNMENT_LOCK = 0x656C79_7374617475L;

    private static final RowMapper<Status> STATUS_MAPPER = new SimplePropertyRowMapper<>(Status.class, ContentRows.CONVERSIONS);

    private final JdbcClient jdbc;

    StatusRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Makes other transactions wait here until this one ends. Each statement runs on a new snapshot, so whatever runs
     * after this sees the statuses that those before it committed.
     */
    public void lockIdAssignment() {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)")
                .param("key", ID_ASSIGNMENT_LOCK)
                .query((rs, rowNum) -> true)
                .single();
    }

    public Optional<UUID> findNewestId() {
        return jdbc.sql("SELECT id FROM status ORDER BY id DESC LIMIT 1").query(UUID.class).optional();
    }

    /**
     * @return whether it was inserted, which it isn't if a status with its URI is already stored
     */
    public boolean insert(Status status) {
        return insert(status, null);
    }

    /**
     * @param activityAt when a remote boost's {@code Announce} first arrived, or null for anything else
     * @return whether it was inserted, which it isn't if a status with its URI is already stored, or if it's a boost and
     * its actor has already boosted the same status
     */
    public boolean insert(Status status, Instant activityAt) {
        return jdbc.sql("""
                        INSERT INTO status (id, uri, actor_id, text, content, spoiler_text, language, visibility, sensitive,
                                            reply, in_reply_to_id, in_reply_to_account_id, in_reply_to_uri, url,
                                            published_at, edited_at, reblog_of_id, activity_at)
                        VALUES (:id, :uri, :actorId, :text, :content, :spoilerText, :language, :visibility, :sensitive,
                                :reply, :inReplyToId, :inReplyToAccountId, :inReplyToUri, :url, :publishedAt, :editedAt,
                                :reblogOfId, :activityAt)
                        ON CONFLICT DO NOTHING
                        """)
                .param("reblogOfId", status.reblogOfId(), Types.OTHER)
                .param("activityAt", activityAt == null ? null : activityAt.atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", status.id())
                .param("uri", status.uri().toString())
                .param("actorId", status.actorId())
                .param("text", status.text())
                .param("content", status.content())
                .param("spoilerText", status.spoilerText())
                .param("language", status.language(), Types.VARCHAR)
                .param("visibility", status.visibility().value())
                .param("sensitive", status.sensitive())
                .param("reply", status.reply())
                .param("inReplyToId", status.inReplyToId(), Types.OTHER)
                .param("inReplyToAccountId", status.inReplyToAccountId(), Types.OTHER)
                .param("inReplyToUri", Objects.toString(status.inReplyToUri(), null), Types.VARCHAR)
                .param("url", Objects.toString(status.url(), null), Types.VARCHAR)
                .param("publishedAt", status.publishedAt().atOffset(ZoneOffset.UTC))
                .param("editedAt", status.editedAt() == null ? null : status.editedAt().atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .update() == 1;
    }

    /**
     * Makes other transactions that take this lock for the same URI wait until this one ends, so that storing a remote status
     * and deleting it can't miss each other.
     */
    public void lockUri(URI uri) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "status:" + uri)
                .query((rs, rowNum) -> true)
                .single();
    }

    public void markRemoteDeleted(URI uri, URI actorUri) {
        jdbc.sql("""
                        INSERT INTO deleted_remote_status (uri, actor_uri) VALUES (:uri, :actorUri)
                        ON CONFLICT (md5(uri), md5(actor_uri)) DO NOTHING
                        """)
                .param("uri", uri.toString())
                .param("actorUri", actorUri.toString())
                .update();
    }

    public boolean isRemoteDeleted(URI uri, URI actorUri) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM deleted_remote_status
                            WHERE md5(uri) = md5(:uri) AND md5(actor_uri) = md5(:actorUri)
                              AND uri = :uri AND actor_uri = :actorUri
                        )
                        """)
                .param("uri", uri.toString())
                .param("actorUri", actorUri.toString())
                .query(Boolean.class)
                .single();
    }

    /**
     * @return whether a status was removed, which it's only if it's a remote status by the actor with this URI
     */
    public boolean deleteRemote(URI uri, URI actorUri) {
        return jdbc.sql("""
                        DELETE FROM status s USING actor a
                        WHERE s.uri = :uri AND a.id = s.actor_id AND a.uri = :actorUri AND NOT a.is_local
                        """)
                .param("uri", uri.toString())
                .param("actorUri", actorUri.toString())
                .update() == 1;
    }

    /**
     * @return the ID of the status if it was changed, which it isn't if it's someone else's, if it was edited at this time
     * or later already, or if it was published at this time or later, so this isn't an edit
     */
    public Optional<UUID> updateRemote(
            URI uri, UUID actorId, String text, String content, String spoilerText, String language, boolean sensitive,
            Instant editedAt) {
        return edit("""
                        uri = :uri AND actor_id = :actorId AND reblog_of_id IS NULL
                        AND (edited_at IS NULL OR edited_at < :editedAt)
                        AND published_at < :editedAt
                        AND NOT EXISTS (SELECT 1 FROM actor a WHERE a.id = :actorId AND a.is_local)""",
                Map.of("uri", uri.toString(), "actorId", actorId),
                text, content, spoilerText, language, sensitive, editedAt);
    }

    /**
     * Replaces the text of one of a local author's own statuses.
     *
     * @return whether the status was changed, which it isn't if it's someone else's, a boost, or isn't local
     */
    public boolean editLocal(
            UUID id, UUID actorId, String text, String content, String spoilerText, String language, boolean sensitive,
            Instant editedAt) {
        return edit("""
                        id = :id AND actor_id = :actorId AND reblog_of_id IS NULL
                        AND EXISTS (SELECT 1 FROM actor a WHERE a.id = :actorId AND a.is_local)""",
                Map.of("id", id, "actorId", actorId),
                text, content, spoilerText, language, sensitive, editedAt).isPresent();
    }

    /**
     * Replaces a status's text, and keeps the version it replaces as an edit, in one statement. The row is locked before
     * it's read, so two edits at once can't both keep the same earlier version: the second waits, then reads the first's.
     * Only the {@link #MAX_VERSIONS} latest versions are kept, since another server can send any number of edits.
     *
     * @param condition which status, from columns of {@code status}
     */
    private Optional<UUID> edit(
            String condition, Map<String, Object> parameters, String text, String content, String spoilerText,
            String language, boolean sensitive, Instant editedAt) {
        var edited = jdbc.sql("""
                        WITH old AS (
                            SELECT id, text, content, spoiler_text, language, sensitive,
                                   coalesce(edited_at, published_at) AS since
                            FROM status WHERE %s
                            FOR NO KEY UPDATE
                        ), replaced AS (
                            UPDATE status s SET text = :text, content = :content, spoiler_text = :spoilerText,
                                                language = :language, sensitive = :sensitive, edited_at = :editedAt
                            FROM old WHERE s.id = old.id
                            RETURNING old.*
                        )
                        INSERT INTO status_edit (status_id, text, content, spoiler_text, language, sensitive, created_at)
                        SELECT id, text, content, spoiler_text, language, sensitive, since FROM replaced
                        RETURNING status_id
                        """.formatted(condition))
                .params(parameters)
                .param("text", text)
                .param("content", content)
                .param("spoilerText", spoilerText)
                .param("language", language, Types.VARCHAR)
                .param("sensitive", sensitive)
                .param("editedAt", editedAt.atOffset(ZoneOffset.UTC))
                .query(UUID.class)
                .optional();
        edited.ifPresent(this::removeOldestEdits);
        return edited;
    }

    /**
     * The status itself holds the current version, so this keeps one fewer edit than that.
     */
    private void removeOldestEdits(UUID statusId) {
        jdbc.sql("""
                        DELETE FROM status_edit WHERE id IN (
                            SELECT id FROM status_edit WHERE status_id = :statusId
                            ORDER BY created_at DESC, id DESC OFFSET :kept
                        )
                        """)
                .param("statusId", statusId)
                .param("kept", MAX_VERSIONS - 1)
                .update();
    }

    /**
     * @return the versions of a status that edits replaced, oldest first
     */
    public List<StatusVersion> findEdits(UUID statusId) {
        return jdbc.sql("""
                        SELECT text, content, spoiler_text, language, sensitive, created_at FROM status_edit
                        WHERE status_id = :statusId ORDER BY created_at, id
                        """)
                .param("statusId", statusId)
                .query((rs, row) -> new StatusVersion(
                        rs.getString("text"),
                        rs.getString("content"),
                        rs.getString("spoiler_text"),
                        rs.getString("language"),
                        rs.getBoolean("sensitive"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /**
     * Locks a status against other edits and deletion until the transaction ends.
     */
    public Optional<Status> findByIdForEdit(UUID id) {
        return jdbc.sql(SELECT + "WHERE s.id = :id FOR NO KEY UPDATE OF s").param("id", id).query(STATUS_MAPPER).optional();
    }

    public boolean isPublicRemote(URI uri, URI actorUri) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM status s JOIN actor a ON a.id = s.actor_id
                            WHERE s.uri = :uri AND a.uri = :actorUri AND NOT a.is_local
                              AND s.visibility IN ('public', 'unlisted')
                        )
                        """)
                .param("uri", uri.toString())
                .param("actorUri", actorUri.toString())
                .query(Boolean.class)
                .single();
    }

    public boolean existsByUri(URI uri) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM status WHERE uri = :uri)")
                .param("uri", uri.toString())
                .query(Boolean.class)
                .single();
    }

    /**
     * @return the URI of the author of a stored status, if the author is neither suspended nor deleted
     */
    public Optional<URI> findAuthorUriByUri(URI uri) {
        return jdbc.sql("""
                        SELECT a.uri FROM status s JOIN actor a ON a.id = s.actor_id
                        WHERE s.uri = :uri AND s.reblog_of_id IS NULL AND a.suspended_at IS NULL AND a.deleted_at IS NULL
                        """)
                .param("uri", uri.toString())
                .query(URI.class)
                .optional();
    }

    /**
     * Finds a status to link a reply to, and stops it from being deleted until the transaction ends. Unlike other lookups,
     * this finds the statuses of suspended authors too, because a link made now still holds if the suspension ends.
     */
    public Optional<Parent> findParentByUriAndLock(URI uri) {
        return jdbc.sql("SELECT id, actor_id FROM status WHERE uri = :uri AND reblog_of_id IS NULL FOR KEY SHARE")
                .param("uri", uri.toString())
                .query(Parent.class)
                .optional();
    }

    /**
     * Finds a status that's about to be deleted, and waits for anything holding it, such as a boost being made of it. Nothing
     * new can hold it until the transaction ends, so what's read about it afterward is all there is to delete with it.
     */
    public Optional<Parent> findForDelete(UUID id) {
        return jdbc.sql("SELECT id, actor_id FROM status WHERE id = :id AND reblog_of_id IS NULL FOR UPDATE")
                .param("id", id)
                .query(Parent.class)
                .optional();
    }

    /**
     * Finds a status by its URI that's about to be deleted, as {@link #findForDelete} does.
     */
    public Optional<Parent> findForDeleteByUri(URI uri) {
        return jdbc.sql("SELECT id, actor_id FROM status WHERE uri = :uri AND reblog_of_id IS NULL FOR UPDATE")
                .param("uri", uri.toString())
                .query(Parent.class)
                .optional();
    }

    /**
     * Links the replies that arrived before their parent did.
     */
    public void linkWaitingReplies(URI parentUri, Parent parent) {
        jdbc.sql("""
                        UPDATE status SET in_reply_to_id = :id, in_reply_to_account_id = :actorId
                        WHERE in_reply_to_uri = :uri AND in_reply_to_id IS NULL
                        """)
                .param("id", parent.id())
                .param("actorId", parent.actorId())
                .param("uri", parentUri.toString())
                .update();
    }

    public record Parent(UUID id, UUID actorId) {
    }

    public List<Status> findByIds(Collection<UUID> ids) {
        return ids.isEmpty() ? List.of() : jdbc.sql(SELECT + "WHERE s.id IN (:ids)")
                .param("ids", ids).query(STATUS_MAPPER).list();
    }

    public Optional<Status> findById(UUID id) {
        return jdbc.sql(SELECT + "WHERE s.id = :id").param("id", id).query(STATUS_MAPPER).optional();
    }

    /**
     * Lists the statuses that a status replies to, oldest first. Replies are linked when their parent arrives, so other
     * servers can make a loop of replies, and the depth limit is also what ends one.
     *
     * @param limit the most to list, counted from the status upward
     */
    public List<Status> findAncestors(UUID id, int limit) {
        return jdbc.sql("""
                        WITH RECURSIVE up (id, parent, depth) AS (
                            SELECT id, in_reply_to_id, 0 FROM status WHERE id = :id
                            UNION ALL
                            SELECT p.id, p.in_reply_to_id, up.depth + 1 FROM status p JOIN up ON p.id = up.parent
                            WHERE up.depth < :limit
                        )
                        """ + SELECT + "JOIN up ON up.id = s.id WHERE up.depth > 0 ORDER BY up.depth DESC")
                .param("id", id)
                .param("limit", limit)
                .query(STATUS_MAPPER)
                .list();
    }

    /**
     * Lists the replies to a status and their replies, depth first, with each status's replies in the order they arrived.
     * A status in a loop of replies is only listed once.
     *
     * @param maxDepth how many levels of replies to follow
     * @param limit    the most to list
     */
    public List<Status> findDescendants(UUID id, int maxDepth, int limit) {
        return jdbc.sql("""
                        WITH RECURSIVE down (id, path) AS (
                            SELECT id, ARRAY[id] FROM status WHERE id = :id
                            UNION ALL
                            SELECT r.id, down.path || r.id FROM status r JOIN down ON r.in_reply_to_id = down.id
                            WHERE cardinality(down.path) <= :maxDepth AND r.id <> ALL (down.path)
                        )
                        """ + SELECT + "JOIN down ON down.id = s.id WHERE s.id <> :id ORDER BY down.path LIMIT :limit")
                .param("id", id)
                .param("maxDepth", maxDepth)
                .param("limit", limit)
                .query(STATUS_MAPPER)
                .list();
    }

    /**
     * Finds a status and stops it from being deleted until the transaction ends, such as so that a reply to it can be
     * inserted without a concurrent delete breaking the foreign key.
     */
    public Optional<Status> findByIdAndLock(UUID id) {
        return jdbc.sql(SELECT + "WHERE s.id = :id FOR KEY SHARE OF s").param("id", id).query(STATUS_MAPPER).optional();
    }

    /**
     * @return the deleted status, or empty if this actor has no status with this ID
     */
    public Optional<Status> delete(UUID id, UUID actorId) {
        return jdbc.sql("WITH replies AS (SELECT (" + COUNT_REPLIES.formatted(":id") + ") AS replies_count) "
                        + "DELETE FROM status s USING replies WHERE s.id = :id AND s.actor_id = :actorId "
                        + "AND s.reblog_of_id IS NULL "
                        + "RETURNING " + COLUMNS + ", replies.replies_count")
                .param("id", id)
                .param("actorId", actorId)
                .query(STATUS_MAPPER)
                .optional();
    }

    /**
     * Removes all of an actor's statuses and tombstones, and the deletions remembered for it. Each batch commits by itself,
     * so that removing a lot of them doesn't hold one long transaction.
     */
    public void removeAllBy(UUID actorId) {
        int removed;
        do {
            removed = jdbc.sql("""
                            DELETE FROM status
                            WHERE id IN (SELECT id FROM status WHERE actor_id = :actorId LIMIT :limit)
                            """)
                    .param("actorId", actorId)
                    .param("limit", REMOVAL_BATCH_SIZE)
                    .update();
        } while (removed == REMOVAL_BATCH_SIZE);
        jdbc.sql("DELETE FROM status_tombstone WHERE actor_id = :actorId").param("actorId", actorId).update();
        jdbc.sql("DELETE FROM status_mention WHERE actor_id = :actorId").param("actorId", actorId).update();
        jdbc.sql("""
                        DELETE FROM deleted_remote_status d USING actor a
                        WHERE a.id = :actorId AND NOT a.is_local AND d.actor_uri = a.uri
                        """)
                .param("actorId", actorId)
                .update();
    }

    /**
     * Removes the statuses of every remote actor on a domain, in batches like {@link #removeAllBy}, and the deletions remembered for
     * them. Remote statuses have no tombstones.
     *
     * @param domain a domain as {@code actor.domain} has it
     */
    public void removeForDomain(String domain) {
        int removed;
        do {
            removed = jdbc.sql("""
                            DELETE FROM status
                            WHERE id IN (
                                SELECT s.id FROM status s
                                JOIN actor a ON a.id = s.actor_id
                                WHERE a.domain = :domain AND NOT a.is_local
                                LIMIT :limit
                            )
                            """)
                    .param("domain", domain)
                    .param("limit", REMOVAL_BATCH_SIZE)
                    .update();
        } while (removed == REMOVAL_BATCH_SIZE);
        // Mentions of the domain's actors in statuses from elsewhere.
        jdbc.sql("""
                        DELETE FROM status_mention m USING actor a
                        WHERE a.id = m.actor_id AND a.domain = :domain AND NOT a.is_local
                        """)
                .param("domain", domain)
                .update();
        jdbc.sql("""
                        DELETE FROM deleted_remote_status d USING actor a
                        WHERE a.domain = :domain AND NOT a.is_local AND d.actor_uri = a.uri
                        """)
                .param("domain", domain)
                .update();
    }

    public void insertTombstone(Status status, Instant deletedAt) {
        jdbc.sql("""
                        INSERT INTO status_tombstone (status_id, uri, actor_id, deleted_at)
                        VALUES (:id, :uri, :actorId, :deletedAt)
                        """)
                .param("id", status.id())
                .param("uri", status.uri().toString())
                .param("actorId", status.actorId())
                .param("deletedAt", deletedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    public Optional<DeletedStatus> findTombstone(UUID id, UUID actorId) {
        return jdbc.sql("SELECT uri, deleted_at FROM status_tombstone WHERE status_id = :id AND actor_id = :actorId")
                .param("id", id)
                .param("actorId", actorId)
                .query(DeletedStatus.class)
                .optional();
    }

    public List<Status> findPublic(StatusOrigin origin, StatusPage page, boolean onlyMedia) {
        var filter = "s.visibility = 'public' AND s.reblog_of_id IS NULL AND " + NOT_A_REPLY_TO_OTHERS + origin(origin)
                + (onlyMedia ? HAS_MEDIA : "");
        return findPage(filter, Map.of(), page);
    }

    /**
     * Replies are listed too, as on Mastodon, since someone following a tag wants every status that uses it.
     */
    public List<Status> findTagged(TagFilter tags, StatusOrigin origin, StatusPage page, boolean onlyMedia) {
        var filter = new StringBuilder("""
                s.visibility = 'public' AND s.reblog_of_id IS NULL
                AND EXISTS (SELECT 1 FROM status_tag t WHERE t.status_id = s.id AND t.name = ANY (CAST(:any AS text[])))""");
        var parameters = new HashMap<String, Object>();
        parameters.put("any", tags.any().toArray(String[]::new));
        if (!tags.all().isEmpty()) {
            filter.append("""
                     AND (SELECT count(*) FROM status_tag t WHERE t.status_id = s.id
                          AND t.name = ANY (CAST(:all AS text[]))) = :allCount""");
            parameters.put("all", tags.all().toArray(String[]::new));
            parameters.put("allCount", tags.all().size());
        }
        if (!tags.none().isEmpty()) {
            filter.append("""
                     AND NOT EXISTS (SELECT 1 FROM status_tag t WHERE t.status_id = s.id
                                     AND t.name = ANY (CAST(:none AS text[])))""");
            parameters.put("none", tags.none().toArray(String[]::new));
        }
        filter.append(origin(origin));
        if (onlyMedia) {
            filter.append(HAS_MEDIA);
        }
        return findPage(filter.toString(), parameters, page);
    }

    private static String origin(StatusOrigin origin) {
        return switch (origin) {
            case ANY -> "";
            case LOCAL -> " AND author.is_local";
            case REMOTE -> " AND NOT author.is_local";
        };
    }

    /**
     * @param followedIds the accounts the viewer follows, which can be empty
     */
    public List<Status> findHome(UUID viewerId, Collection<UUID> followedIds, StatusPage page) {
        var filter = """
                (s.actor_id = :viewer
                 OR (s.actor_id = ANY (CAST(:followed AS uuid[]))
                     AND ((s.visibility IN ('public', 'unlisted', 'private')
                           AND (NOT s.reply OR s.in_reply_to_account_id = s.actor_id OR s.in_reply_to_account_id = :viewer
                                OR s.in_reply_to_account_id = ANY (CAST(:followed AS uuid[]))))
                          OR (s.visibility = 'direct' AND""" + MENTIONS_VIEWER + "))))";
        // One array parameter, rather than one for each account, which PostgreSQL's driver limits the number of.
        var followed = followedIds.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
        return findPage(filter, Map.of("viewer", viewerId, "followed", followed), page);
    }

    /**
     * @param visibilities which statuses the viewer can see
     * @param viewer       who's looking, who can also see the followers-only and direct statuses that mention them, or null
     */
    public List<Status> findByAccount(
            UUID actorId, Collection<Visibility> visibilities, UUID viewer, boolean excludeReplies,
            boolean excludeReblogs, boolean onlyMedia, StatusPage page) {
        var parameters = new HashMap<String, Object>();
        parameters.put("actorId", actorId);
        parameters.put("visibilities", visibilities.stream().map(Visibility::value).toList());
        var filter = new StringBuilder("s.actor_id = :actorId AND (s.visibility IN (:visibilities)");
        if (viewer != null) {
            filter.append(" OR (s.visibility IN ('private', 'direct') AND").append(MENTIONS_VIEWER).append(")");
            parameters.put("viewer", viewer);
        }
        filter.append(")");
        if (excludeReplies) {
            filter.append(" AND ").append(NOT_A_REPLY_TO_OTHERS);
        }
        if (excludeReblogs) {
            filter.append(" AND s.reblog_of_id IS NULL");
        }
        if (onlyMedia) {
            filter.append(HAS_MEDIA);
        }
        return findPage(filter.toString(), parameters, page);
    }

    /**
     * Counts what {@link #findByAccount} lists for these visibilities, with no viewer and boosts included.
     */
    public long countByAccount(UUID actorId, Collection<Visibility> visibilities) {
        return jdbc.sql("SELECT count(*) " + FROM_ACTIVE + "WHERE s.actor_id = :actorId AND s.visibility IN (:visibilities)")
                .param("actorId", actorId)
                .param("visibilities", visibilities.stream().map(Visibility::value).toList())
                .query(Long.class)
                .single();
    }

    /**
     * @param mentions each account mentioned, and whether that's silently
     */
    public void insertMentions(UUID statusId, Map<UUID, Boolean> mentions) {
        if (mentions.isEmpty()) {
            return;
        }
        var entries = List.copyOf(mentions.entrySet());
        // One statement, rather than one for each of up to a hundred mentions a note from another server can have.
        jdbc.sql("""
                        INSERT INTO status_mention (status_id, actor_id, silent)
                        SELECT :statusId, m.actor_id, m.silent
                        FROM unnest(CAST(:actorIds AS uuid[]), CAST(:silent AS boolean[])) AS m (actor_id, silent)
                        ON CONFLICT DO NOTHING
                        """)
                .param("statusId", statusId)
                .param("actorIds", entries.stream()
                        .map(entry -> entry.getKey().toString())
                        .collect(Collectors.joining(",", "{", "}")))
                .param("silent", entries.stream()
                        .map(entry -> entry.getValue().toString())
                        .collect(Collectors.joining(",", "{", "}")))
                .update();
    }

    /**
     * Makes these the accounts a status shows as mentioned. Accounts it mentioned before stay as silent mentions rather than
     * going, so that an edit can't take the status away from anyone who was sent it, and its {@code Delete} still reaches
     * them.
     *
     * @return the accounts that it didn't mention before, silently or not
     */
    public List<UUID> replaceMentions(UUID statusId, Collection<UUID> current) {
        var ids = current.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
        jdbc.sql("""
                        UPDATE status_mention SET silent = NOT (actor_id = ANY (CAST(:ids AS uuid[])))
                        WHERE status_id = :statusId
                        """)
                .param("statusId", statusId)
                .param("ids", ids)
                .update();
        return jdbc.sql("""
                        INSERT INTO status_mention (status_id, actor_id, silent)
                        SELECT :statusId, m.actor_id, false FROM unnest(CAST(:ids AS uuid[])) AS m (actor_id)
                        ON CONFLICT DO NOTHING
                        RETURNING actor_id
                        """)
                .param("statusId", statusId)
                .param("ids", ids)
                .query(UUID.class)
                .list();
    }

    /**
     * @return the accounts each status shows as mentioned, leaving out silent mentions, and the accounts that are suspended
     * or deleted
     */
    public Map<UUID, List<UUID>> findMentions(Collection<UUID> statusIds) {
        if (statusIds.isEmpty()) {
            return Map.of();
        }
        var mentions = new HashMap<UUID, List<UUID>>();
        jdbc.sql("""
                        SELECT m.status_id, m.actor_id FROM status_mention m
                        JOIN actor a ON a.id = m.actor_id AND a.suspended_at IS NULL AND a.deleted_at IS NULL
                        WHERE m.status_id IN (:statusIds) AND NOT m.silent
                        ORDER BY m.status_id, a.username, a.domain
                        """)
                .param("statusIds", statusIds)
                .query(rs -> {
                    mentions.computeIfAbsent(rs.getObject("status_id", UUID.class), id -> new ArrayList<>())
                            .add(rs.getObject("actor_id", UUID.class));
                });
        return mentions;
    }

    /**
     * @param names normalized names
     */
    public void insertTags(UUID statusId, Collection<String> names) {
        if (names.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO status_tag (status_id, name)
                        SELECT :statusId, t.name FROM unnest(CAST(:names AS text[])) AS t (name)
                        ON CONFLICT DO NOTHING
                        """)
                .param("statusId", statusId)
                .param("names", names.toArray(String[]::new))
                .update();
    }

    /**
     * Unlike mentions, tags that an edit takes out go, since a tag doesn't decide who can see a status.
     */
    public void replaceTags(UUID statusId, Collection<String> names) {
        jdbc.sql("DELETE FROM status_tag WHERE status_id = :statusId AND NOT (name = ANY (CAST(:names AS text[])))")
                .param("statusId", statusId)
                .param("names", names.toArray(String[]::new))
                .update();
        insertTags(statusId, names);
    }

    /**
     * @return each status's tags, by name, for each status that has any
     */
    public Map<UUID, List<String>> findTags(Collection<UUID> statusIds) {
        if (statusIds.isEmpty()) {
            return Map.of();
        }
        var tags = new HashMap<UUID, List<String>>();
        jdbc.sql("SELECT status_id, name FROM status_tag WHERE status_id IN (:statusIds) ORDER BY status_id, name")
                .param("statusIds", statusIds)
                .query(rs -> {
                    tags.computeIfAbsent(rs.getObject("status_id", UUID.class), id -> new ArrayList<>())
                            .add(rs.getString("name"));
                });
        return tags;
    }

    /**
     * Tags whose names start with a prefix, used by the most public statuses first. Only statuses that could be listed
     * count, so a tag that only a suspended account used isn't found.
     *
     * @param prefix a normalized name
     */
    public List<String> searchTags(String prefix, int limit, int offset) {
        // Of the characters LIKE treats specially, only _ can be in a name.
        var pattern = prefix.replace("_", "\\_") + "%";
        return jdbc.sql("SELECT t.name " + FROM_ACTIVE + """
                        JOIN status_tag t ON t.status_id = s.id
                        WHERE s.visibility = 'public' AND t.name LIKE :pattern
                        GROUP BY t.name
                        ORDER BY count(*) DESC, t.name
                        LIMIT :limit OFFSET :offset
                        """)
                .param("pattern", pattern)
                .param("limit", limit)
                .param("offset", offset)
                .query(String.class)
                .list();
    }

    /**
     * @return the days since {@code since} on which each tag was used, by public statuses from active accounts
     */
    public Map<String, List<TagDay>> tagDays(Collection<String> names, Instant since) {
        if (names.isEmpty()) {
            return Map.of();
        }
        var days = new HashMap<String, List<TagDay>>();
        // A status's ID comes from when it arrived, or for a backfilled one from when it was published, so no status
        // published since then has an earlier ID. Bounding the ID lets the index skip the tag's older statuses.
        jdbc.sql("""
                        SELECT t.name, (s.published_at AT TIME ZONE 'UTC')::date AS day,
                               count(*) AS uses, count(DISTINCT s.actor_id) AS accounts
                        """ + FROM_ACTIVE + """
                        JOIN status_tag t ON t.status_id = s.id
                        WHERE s.visibility = 'public' AND s.published_at >= :since
                        AND t.name = ANY (CAST(:names AS text[])) AND t.status_id >= :lowestId
                        GROUP BY t.name, day
                        """)
                .param("names", names.toArray(String[]::new))
                .param("lowestId", new UUID(since.toEpochMilli() << 16, 0L))
                .param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .query(rs -> {
                    days.computeIfAbsent(rs.getString("name"), name -> new ArrayList<>()).add(new TagDay(
                            rs.getObject("day", LocalDate.class), rs.getLong("uses"), rs.getLong("accounts")));
                });
        return days;
    }

    /**
     * Every account a status mentions, silently or not, including those that are suspended, for what has to reach whoever
     * was sent it.
     */
    public List<UUID> findAllMentioned(UUID statusId) {
        return jdbc.sql("SELECT actor_id FROM status_mention WHERE status_id = :statusId")
                .param("statusId", statusId)
                .query(UUID.class)
                .list();
    }

    /**
     * @return which of these statuses mention the viewer, silently or not
     */
    public Set<UUID> mentioning(Collection<UUID> statusIds, UUID viewer) {
        if (statusIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql(
                        "SELECT status_id FROM status_mention WHERE status_id IN (:statusIds) AND actor_id = :viewer")
                .param("statusIds", statusIds)
                .param("viewer", viewer)
                .query(UUID.class)
                .list());
    }

    /**
     * @return the stats of each account that has any counted statuses
     */
    public Map<UUID, AccountStats> statsByAccount(Collection<UUID> actorIds) {
        if (actorIds.isEmpty()) {
            return Map.of();
        }
        var stats = new HashMap<UUID, AccountStats>();
        jdbc.sql("""
                        SELECT c.actor_id, c.statuses_count, (
                            SELECT max(s.published_at)
                            FROM status s
                            WHERE s.actor_id = c.actor_id AND s.visibility <> 'direct'
                        ) AS last_status_at
                        FROM actor_status_count c
                        WHERE c.actor_id IN (:actorIds) AND c.statuses_count > 0
                        """)
                .param("actorIds", actorIds)
                .query(rs -> {
                    stats.put(rs.getObject("actor_id", UUID.class), new AccountStats(
                            rs.getLong("statuses_count"),
                            rs.getObject("last_status_at", OffsetDateTime.class).toInstant()));
                });
        return stats;
    }

    public long countLocalExcludingDirect() {
        return jdbc.sql("""
                        SELECT coalesce(sum(c.statuses_count), 0)::bigint FROM actor_status_count c
                        JOIN actor ON actor.id = c.actor_id
                        WHERE actor.is_local AND actor.suspended_at IS NULL AND actor.deleted_at IS NULL
                        """)
                .query(Long.class)
                .single();
    }

    private List<Status> findPage(String filter, Map<String, ?> parameters, StatusPage page) {
        var sql = new StringBuilder(SELECT).append("WHERE ").append(filter);
        var cursors = new HashMap<String, Object>(parameters);
        if (page.maxId() != null) {
            sql.append(" AND s.id < :maxId");
            cursors.put("maxId", page.maxId());
        }
        // Predicates are only added when needed, because PostgreSQL can't determine the type of a null parameter.
        var ascending = page.minId() != null;
        if (ascending) {
            sql.append(" AND s.id > :minId");
            cursors.put("minId", page.minId());
        } else if (page.sinceId() != null) {
            sql.append(" AND s.id > :sinceId");
            cursors.put("sinceId", page.sinceId());
        }
        sql.append(ascending ? " ORDER BY s.id ASC" : " ORDER BY s.id DESC").append(" LIMIT :limit");
        cursors.put("limit", page.limit());

        var statuses = jdbc.sql(sql.toString()).params(cursors).query(STATUS_MAPPER).list();
        return ascending ? statuses.reversed() : statuses;
    }
}
