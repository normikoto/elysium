package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.content.QuoteApproval;
import dev.biribiri.elysium.content.QuoteState;
import dev.biribiri.elysium.content.StatusQuote;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class QuoteRepository {

    private static final String COLUMNS = """
            q.status_id, q.quoted_uri, q.quoted_status_id, q.quoted_account_id, q.linked, q.state, q.legacy,
            q.approval_uri, q.stamp_id, q.request_uri""";

    private static final RowMapper<StatusQuote> MAPPER = QuoteRepository::map;

    private final JdbcClient jdbc;

    QuoteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static StatusQuote map(ResultSet rs, int row) throws SQLException {
        return new StatusQuote(
                rs.getObject("status_id", UUID.class),
                URI.create(rs.getString("quoted_uri")),
                rs.getObject("quoted_status_id", UUID.class),
                rs.getObject("quoted_account_id", UUID.class),
                rs.getBoolean("linked"),
                QuoteState.fromValue(rs.getString("state")),
                rs.getBoolean("legacy"),
                ContentRows.uriOrNull(rs.getString("approval_uri")),
                rs.getObject("stamp_id", UUID.class),
                ContentRows.uriOrNull(rs.getString("request_uri")));
    }

    private static JdbcClient.StatementSpec bindQuoted(
            JdbcClient.StatementSpec statement, Optional<StatusRepository.Parent> quoted) {
        return statement
                .param("quotedStatusId", quoted.map(StatusRepository.Parent::id).orElse(null), Types.OTHER)
                .param("quotedAccountId", quoted.map(StatusRepository.Parent::actorId).orElse(null), Types.OTHER)
                .param("linked", quoted.isPresent());
    }

    /**
     * Does nothing if the status already quotes something.
     *
     * @param quoted the quoted status, if it's stored, which also makes the quote linked
     */
    public void insert(
            UUID statusId, URI quotedUri, Optional<StatusRepository.Parent> quoted, QuoteState state, boolean legacy,
            URI approvalUri, UUID stampId, URI requestUri) {
        bindQuoted(jdbc.sql("""
                        INSERT INTO status_quote (status_id, quoted_uri, quoted_status_id, quoted_account_id, linked, state,
                                                  legacy, approval_uri, stamp_id, request_uri)
                        VALUES (:statusId, :quotedUri, :quotedStatusId, :quotedAccountId, :linked, :state, :legacy,
                                :approvalUri, :stampId, :requestUri)
                        ON CONFLICT DO NOTHING
                        """), quoted)
                .param("statusId", statusId)
                .param("quotedUri", quotedUri.toString())
                .param("state", state.value())
                .param("legacy", legacy)
                .param("approvalUri", Objects.toString(approvalUri, null), Types.VARCHAR)
                .param("stampId", stampId, Types.OTHER)
                .param("requestUri", Objects.toString(requestUri, null), Types.VARCHAR)
                .update();
    }

    public Optional<StatusQuote> find(UUID statusId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM status_quote q WHERE q.status_id = :id")
                .param("id", statusId).query(MAPPER).optional();
    }

    public Optional<StatusQuote> findAndLock(UUID statusId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM status_quote q WHERE q.status_id = :id FOR UPDATE")
                .param("id", statusId).query(MAPPER).optional();
    }

    public Map<UUID, StatusQuote> findAll(Collection<UUID> statusIds) {
        if (statusIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM status_quote q WHERE q.status_id IN (:ids)")
                .param("ids", statusIds).query(MAPPER).list().stream()
                .collect(Collectors.toMap(StatusQuote::statusId, quote -> quote));
    }

    public Optional<StatusQuote> findByRequestAndLock(URI requestUri) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM status_quote q "
                        + "WHERE md5(q.request_uri) = md5(:uri) AND q.request_uri = :uri FOR UPDATE")
                .param("uri", requestUri.toString()).query(MAPPER).optional();
    }

    public Optional<StatusQuote> findByStamp(UUID stampId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM status_quote q WHERE q.stamp_id = :stamp")
                .param("stamp", stampId).query(MAPPER).optional();
    }

    /**
     * @return the quotes that were approved with this {@code QuoteAuthorization}, locked
     */
    public List<StatusQuote> findByApprovalAndLock(URI approvalUri) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM status_quote q "
                        + "WHERE md5(q.approval_uri) = md5(:uri) AND q.approval_uri = :uri FOR UPDATE")
                .param("uri", approvalUri.toString()).query(MAPPER).list();
    }

    public boolean isKnownRequest(URI requestUri) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM status_quote "
                        + "WHERE md5(request_uri) = md5(:uri) AND request_uri = :uri)")
                .param("uri", requestUri.toString()).query(Boolean.class).single();
    }

    public boolean isKnownApproval(URI approvalUri) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM status_quote "
                        + "WHERE md5(approval_uri) = md5(:uri) AND approval_uri = :uri)")
                .param("uri", approvalUri.toString()).query(Boolean.class).single();
    }

    /**
     * Links the quotes that were stored before the status they quote.
     *
     * @return the quoting statuses
     */
    public List<UUID> linkWaiting(URI quotedUri, UUID quotedStatusId, UUID quotedAccountId) {
        return jdbc.sql("""
                        UPDATE status_quote SET quoted_status_id = :id, quoted_account_id = :actorId, linked = true
                        WHERE md5(quoted_uri) = md5(:uri) AND quoted_uri = :uri AND NOT linked
                        RETURNING status_id
                        """)
                .param("id", quotedStatusId)
                .param("actorId", quotedAccountId)
                .param("uri", quotedUri.toString())
                .query(UUID.class)
                .list();
    }

    public void changeState(UUID statusId, QuoteState to, boolean legacy, URI approvalUri, UUID stampId) {
        jdbc.sql("""
                        UPDATE status_quote SET state = :to, legacy = :legacy, approval_uri = :approvalUri,
                                                stamp_id = :stampId
                        WHERE status_id = :statusId
                        """)
                .param("statusId", statusId)
                .param("to", to.value())
                .param("legacy", legacy)
                .param("approvalUri", Objects.toString(approvalUri, null), Types.VARCHAR)
                .param("stampId", stampId, Types.OTHER)
                .update();
    }

    public void replace(UUID statusId, URI quotedUri, Optional<StatusRepository.Parent> quoted, boolean legacy,
                        URI approvalUri) {
        bindQuoted(jdbc.sql("""
                        UPDATE status_quote SET quoted_uri = :quotedUri, quoted_status_id = :quotedStatusId,
                                                quoted_account_id = :quotedAccountId, linked = :linked, state = 'pending',
                                                legacy = :legacy, approval_uri = :approvalUri, stamp_id = NULL
                        WHERE status_id = :statusId
                        """), quoted)
                .param("statusId", statusId)
                .param("quotedUri", quotedUri.toString())
                .param("legacy", legacy)
                .param("approvalUri", Objects.toString(approvalUri, null), Types.VARCHAR)
                .update();
    }

    public void setRequest(UUID statusId, URI requestUri) {
        jdbc.sql("UPDATE status_quote SET request_uri = :uri WHERE status_id = :statusId")
                .param("statusId", statusId).param("uri", requestUri.toString()).update();
    }

    public void delete(UUID statusId) {
        jdbc.sql("DELETE FROM status_quote WHERE status_id = :statusId").param("statusId", statusId).update();
    }

    /**
     * @param approval null for none, which a status from before quotes has
     */
    public void setPolicy(UUID statusId, QuoteApproval approval) {
        jdbc.sql("UPDATE status SET quote_automatic = CAST(:automatic AS text[]), quote_manual = CAST(:manual AS text[]) "
                        + "WHERE id = :id")
                .param("id", statusId)
                .param("automatic", approval == null ? null : ContentRows.array(approval.automatic()), Types.VARCHAR)
                .param("manual", approval == null ? null : ContentRows.array(approval.manual()), Types.VARCHAR)
                .update();
    }

    public Map<UUID, Long> acceptedCounts(Collection<UUID> statusIds) {
        if (statusIds.isEmpty()) {
            return Map.of();
        }
        var counts = new HashMap<UUID, Long>();
        jdbc.sql("""
                        SELECT q.quoted_status_id, count(*) AS total
                        FROM status_quote q JOIN status s ON s.id = q.status_id JOIN actor a ON a.id = s.actor_id
                        WHERE q.quoted_status_id IN (:ids) AND q.state = 'accepted'
                          AND s.visibility IN ('public', 'unlisted') AND
                        """ + LikeRepository.eligible("a") + " GROUP BY q.quoted_status_id")
                .param("ids", statusIds)
                .query((rs, row) -> counts.put(rs.getObject("quoted_status_id", UUID.class), rs.getLong("total")))
                .list();
        return counts;
    }

    /**
     * Lists the accepted quotes of a status, newest first, by authors who are active.
     *
     * @return the quoting statuses
     */
    public List<UUID> pageAccepted(UUID quotedStatusId, UUID maxId, UUID sinceId, int limit) {
        return jdbc.sql("""
                        SELECT q.status_id FROM status_quote q JOIN status s ON s.id = q.status_id
                        JOIN actor a ON a.id = s.actor_id
                        WHERE q.quoted_status_id = :quoted AND q.state = 'accepted' AND
                        """ + LikeRepository.eligible("a")
                        + (maxId == null ? "" : " AND q.status_id < :maxId")
                        + (sinceId == null ? "" : " AND q.status_id > :sinceId")
                        + " ORDER BY q.status_id DESC LIMIT :limit")
                .param("quoted", quotedStatusId)
                .param("maxId", maxId, Types.OTHER)
                .param("sinceId", sinceId, Types.OTHER)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /**
     * The local statuses with an accepted quote of this status, and their authors.
     */
    public Map<UUID, UUID> findLocalAcceptedQuotes(UUID quotedStatusId) {
        var quotes = new HashMap<UUID, UUID>();
        jdbc.sql("""
                        SELECT s.id, s.actor_id FROM status_quote q JOIN status s ON s.id = q.status_id
                        JOIN actor a ON a.id = s.actor_id
                        WHERE q.quoted_status_id = :quoted AND q.state = 'accepted' AND a.is_local
                        """)
                .param("quoted", quotedStatusId)
                .query((rs, row) -> quotes.put(rs.getObject("id", UUID.class), rs.getObject("actor_id", UUID.class)))
                .list();
        return quotes;
    }

    /**
     * Marks a local status's {@code Note} as changed without an edit, in a later second than any version of it sent
     * before, since other servers are sent whole seconds and ignore what isn't later than what they have.
     *
     * @return when, or empty if there's no such status
     */
    public Optional<Instant> touchNote(UUID statusId) {
        return jdbc.sql("""
                        UPDATE status SET note_updated_at = greatest(
                            date_trunc('milliseconds', now()),
                            date_trunc('second', greatest(published_at, edited_at, note_updated_at)) + interval '1 second')
                        WHERE id = :id
                        RETURNING note_updated_at
                        """)
                .param("id", statusId)
                .query((rs, row) -> rs.getObject("note_updated_at", OffsetDateTime.class).toInstant())
                .optional();
    }
}
