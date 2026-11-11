package dev.biribiri.elysium.inbox.internal;

import dev.biribiri.elysium.inbox.Conversation;
import dev.biribiri.elysium.inbox.InboxPage;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class ConversationRepository {
    private static final String SELECT = """
            SELECT c.*, latest.id AS last_status_id,
                   (latest.actor_id <> c.owner_id AND (c.last_read_id IS NULL OR latest.id > c.last_read_id)) AS unread
            FROM inbox_conversation c CROSS JOIN LATERAL (
                SELECT s.id, s.actor_id FROM inbox_conversation_status cs
                JOIN status s ON s.id = cs.status_id JOIN actor a ON a.id = s.actor_id
                WHERE cs.conversation_id = c.id AND
            """ + InboxSql.active("a") + " AND " + InboxSql.notHidden("a") + " AND " + InboxSql.visible("s") + """
                 ORDER BY s.id DESC LIMIT 1
            ) latest
            WHERE c.owner_id = :viewer AND (c.hidden_through_id IS NULL OR latest.id > c.hidden_through_id)
            """;
    private static final RowMapper<Conversation> MAPPER = (rs, row) -> new Conversation(
            rs.getObject("id", UUID.class), rs.getBoolean("unread"),
            Arrays.asList((UUID[]) rs.getArray("participant_ids").getArray()), rs.getObject("last_status_id", UUID.class));
    private final JdbcClient jdbc;

    ConversationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Source> source(UUID id) {
        return source(id, false);
    }

    public Optional<Source> holdSource(UUID id) {
        return source(id, true);
    }

    private Optional<Source> source(UUID id, boolean hold) {
        return jdbc.sql("""
                        SELECT s.id, s.actor_id, s.thread_id, s.visibility FROM status s JOIN actor a ON a.id = s.actor_id
                        WHERE s.id = :id AND
                        """ + InboxSql.active("a") + (hold ? " FOR KEY SHARE OF s" : ""))
                .param("id", id).query((rs, n) -> new Source(rs.getObject("id", UUID.class),
                        rs.getObject("actor_id", UUID.class), rs.getObject("thread_id", UUID.class),
                        "direct".equals(rs.getString("visibility")))).optional();
    }

    /** Parent actors must be held before their statuses/follows, matching the order of cascading actor deletion. */
    public void holdActors(List<UUID> ids) {
        if (ids.isEmpty()) return;
        jdbc.sql("SELECT id FROM actor WHERE id IN (:ids) ORDER BY id FOR KEY SHARE")
                .param("ids", ids).query(UUID.class).list();
    }

    public List<UUID> mentions(UUID statusId) {
        return jdbc.sql("SELECT m.actor_id FROM status_mention m JOIN actor a ON a.id = m.actor_id "
                + "WHERE m.status_id = :id AND NOT m.silent AND " + InboxSql.notGone("a") + " ORDER BY m.actor_id")
                .param("id", statusId).query(UUID.class).list();
    }

    public List<UUID> localRecipients(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("SELECT a.id FROM actor a WHERE a.id IN (:ids) AND a.is_local AND " + InboxSql.active("a"))
                .param("ids", ids).query(UUID.class).list();
    }

    public boolean currentFollow(UUID id, UUID sender, UUID recipient) {
        return currentFollow(id, sender, recipient, "accepted");
    }

    /**
     * Like {@link #currentFollow}, for a request that's still waiting for the recipient to approve it.
     */
    public boolean currentFollowRequest(UUID id, UUID sender, UUID recipient) {
        return currentFollow(id, sender, recipient, "pending");
    }

    private boolean currentFollow(UUID id, UUID sender, UUID recipient, String state) {
        return !jdbc.sql("""
                        SELECT f.id FROM follow f JOIN actor a ON a.id = f.follower_id
                        JOIN actor recipient ON recipient.id = f.followed_id
                        WHERE f.id = :id AND f.follower_id = :sender AND f.followed_id = :recipient
                        AND f.state = :state AND recipient.is_local AND
                        """ + InboxSql.active("a") + " AND " + InboxSql.active("recipient") + " FOR KEY SHARE OF f")
                .param("id", id).param("sender", sender).param("recipient", recipient).param("state", state)
                .query(UUID.class).list().isEmpty();
    }

    public boolean currentLike(UUID id, UUID sender, UUID statusId) {
        return jdbc.sql("""
                        SELECT l.id FROM status_like l JOIN actor a ON a.id = l.actor_id
                        WHERE l.id = :id AND l.actor_id = :sender AND l.status_id = :status AND
                        """ + InboxSql.active("a") + " FOR KEY SHARE OF l")
                .param("id", id).param("sender", sender).param("status", statusId)
                .query(UUID.class).optional().isPresent();
    }

    public boolean currentReblog(UUID id, UUID sender, UUID statusId) {
        return jdbc.sql("""
                        SELECT b.id FROM status b JOIN actor a ON a.id = b.actor_id
                        WHERE b.id = :id AND b.actor_id = :sender AND b.reblog_of_id = :status AND
                        """ + InboxSql.active("a") + " FOR KEY SHARE OF b")
                .param("id", id).param("sender", sender).param("status", statusId)
                .query(UUID.class).optional().isPresent();
    }

    public void add(Source source, UUID viewer, List<UUID> people) {
        var participants = people.stream().filter(id -> !id.equals(viewer)).distinct().map(UUID::toString).sorted().toArray(String[]::new);
        var id = jdbc.sql("""
                        INSERT INTO inbox_conversation (id, owner_id, thread_id, participant_ids)
                        VALUES (:id, :viewer, :thread, CAST(:participants AS uuid[]))
                        ON CONFLICT (owner_id, thread_id, participant_ids) DO UPDATE SET owner_id = EXCLUDED.owner_id
                        RETURNING id
                        """).param("id", UUID.randomUUID()).param("viewer", viewer).param("thread", source.threadId())
                .param("participants", participants).query(UUID.class).single();
        var inserted = jdbc.sql("""
                        INSERT INTO inbox_conversation_status VALUES (:conversation, :status) ON CONFLICT DO NOTHING
                        """).param("conversation", id).param("status", source.id()).update() != 0;
        if (inserted && source.authorId().equals(viewer)) {
            jdbc.sql("UPDATE inbox_conversation SET last_read_id = greatest(last_read_id, :status) WHERE id = :id")
                    .param("status", source.id()).param("id", id).update();
        }
    }

    public List<Conversation> page(UUID viewer, InboxPage page) {
        var parameters = new HashMap<String, Object>();
        parameters.put("viewer", viewer);
        parameters.put("limit", page.limit());
        var sql = new StringBuilder(SELECT);
        InboxSql.cursors(sql, parameters, page, "latest.id");
        sql.append(page.minId() == null ? " ORDER BY latest.id DESC" : " ORDER BY latest.id ASC").append(" LIMIT :limit");
        var found = jdbc.sql(sql.toString()).params(parameters).query(MAPPER).list();
        return page.minId() == null ? found : found.reversed();
    }

    public Optional<Conversation> find(UUID viewer, UUID id) {
        return jdbc.sql(SELECT + " AND c.id = :id").param("viewer", viewer).param("id", id).query(MAPPER).optional();
    }

    public Optional<Conversation> read(UUID viewer, UUID id) {
        var found = find(viewer, id);
        found.ifPresent(c -> jdbc.sql("UPDATE inbox_conversation SET last_read_id = :last WHERE owner_id = :viewer AND id = :id")
                .param("last", c.lastStatusId()).param("viewer", viewer).param("id", id).update());
        return found.map(c -> new Conversation(c.id(), false, c.accountIds(), c.lastStatusId()));
    }

    public boolean hide(UUID viewer, UUID id) {
        var found = find(viewer, id);
        // Include even statuses currently hidden by suspension, so restoring an account does not undo a dismissal.
        found.ifPresent(c -> jdbc.sql("""
                        UPDATE inbox_conversation SET hidden_through_id = (
                            SELECT id FROM status ORDER BY id DESC LIMIT 1)
                        WHERE owner_id = :viewer AND id = :id
                        """).param("viewer", viewer).param("id", id).update());
        return found.isPresent();
    }

    /** Read current status identities, because merge events themselves can be delivered out of order. */
    public void reconcile(UUID previous, UUID target) {
        holdActors(jdbc.sql("""
                        SELECT DISTINCT person FROM inbox_conversation c
                        CROSS JOIN LATERAL unnest(c.participant_ids || ARRAY[c.owner_id]) person
                        WHERE c.thread_id = :previous OR c.thread_id = :target
                        """).param("previous", previous).param("target", target).query(UUID.class).list());
        var ids = jdbc.sql("SELECT id FROM inbox_conversation WHERE thread_id = :previous OR thread_id = :target")
                .param("previous", previous).param("target", target).query(UUID.class).list();
        for (var id : ids) {
            var thread = jdbc.sql("""
                            SELECT s.thread_id FROM inbox_conversation_status cs JOIN status s ON s.id = cs.status_id
                            WHERE cs.conversation_id = :id LIMIT 1
                            """).param("id", id).query(UUID.class).optional();
            thread.ifPresent(value -> rekey(id, value, Set.of()));
        }
    }

    /** @return the conversation that's left, which is another one when this one turned out to duplicate it */
    private UUID rekey(UUID id, UUID thread, Set<UUID> removed) {
        var rows = jdbc.sql("SELECT owner_id, participant_ids FROM inbox_conversation WHERE id = :id")
                .param("id", id).query((rs, n) -> new Key(rs.getObject("owner_id", UUID.class),
                        Arrays.asList((UUID[]) rs.getArray("participant_ids").getArray()))).list();
        if (rows.isEmpty()) return id;
        var key = rows.getFirst();
        var participants = key.people().stream().filter(p -> !removed.contains(p)).map(UUID::toString).toArray(String[]::new);
        var duplicate = jdbc.sql("""
                        SELECT id FROM inbox_conversation WHERE owner_id = :owner AND thread_id = :thread
                        AND participant_ids = CAST(:people AS uuid[]) AND id <> :id
                        """).param("owner", key.owner()).param("thread", thread).param("people", participants)
                .param("id", id).query(UUID.class).optional();
        if (duplicate.isPresent()) {
            var keep = id.toString().compareTo(duplicate.get().toString()) < 0 ? id : duplicate.get();
            var remove = keep.equals(id) ? duplicate.get() : id;
            jdbc.sql("""
                            INSERT INTO inbox_conversation_status SELECT :keep, status_id FROM inbox_conversation_status
                            WHERE conversation_id = :remove ON CONFLICT DO NOTHING
                            """).param("keep", keep).param("remove", remove).update();
            jdbc.sql("""
                            UPDATE inbox_conversation c SET last_read_id = greatest(c.last_read_id, other.last_read_id),
                            hidden_through_id = greatest(c.hidden_through_id, other.hidden_through_id)
                            FROM inbox_conversation other WHERE c.id = :keep AND other.id = :remove
                            """).param("keep", keep).param("remove", remove).update();
            jdbc.sql("DELETE FROM inbox_conversation WHERE id = :id").param("id", remove).update();
            id = keep;
        }
        jdbc.sql("UPDATE inbox_conversation SET thread_id = :thread, participant_ids = CAST(:people AS uuid[]) WHERE id = :id")
                .param("thread", thread).param("people", participants).param("id", id).update();
        return id;
    }

    /** Every conversation an actor could have touched names them as its owner or a participant, so nothing else is scanned. */
    public void removeForActors(List<UUID> actorIds) {
        var ids = actorIds.stream().map(UUID::toString).toArray(String[]::new);
        holdActors(jdbc.sql("""
                        SELECT DISTINCT person FROM inbox_conversation c
                        CROSS JOIN LATERAL unnest(c.participant_ids || ARRAY[c.owner_id]) person
                        WHERE c.owner_id = ANY(CAST(:ids AS uuid[])) OR c.participant_ids && CAST(:ids AS uuid[])
                        """).param("ids", ids).query(UUID.class).list());
        jdbc.sql("""
                        DELETE FROM inbox_notification
                        WHERE recipient_id = ANY(CAST(:ids AS uuid[])) OR sender_id = ANY(CAST(:ids AS uuid[]))
                        """).param("ids", ids).update();
        jdbc.sql("DELETE FROM inbox_marker WHERE owner_id = ANY(CAST(:ids AS uuid[]))").param("ids", ids).update();
        jdbc.sql("DELETE FROM inbox_follow_group WHERE recipient_id = ANY(CAST(:ids AS uuid[]))").param("ids", ids).update();
        jdbc.sql("DELETE FROM inbox_conversation WHERE owner_id = ANY(CAST(:ids AS uuid[]))").param("ids", ids).update();
        jdbc.sql("""
                        DELETE FROM inbox_conversation_status cs USING status s
                        WHERE cs.status_id = s.id AND s.actor_id = ANY(CAST(:ids AS uuid[]))
                        """).param("ids", ids).update();
        var affected = jdbc.sql("SELECT id, thread_id FROM inbox_conversation WHERE participant_ids && CAST(:ids AS uuid[])")
                .param("ids", ids).query((rs, n) -> new UUID[]{rs.getObject("id", UUID.class), rs.getObject("thread_id", UUID.class)})
                .list();
        var removed = Set.copyOf(actorIds);
        var left = new HashSet<UUID>();
        for (var pair : affected) left.add(rekey(pair[0], pair[1], removed));
        if (left.isEmpty()) return;
        jdbc.sql("""
                        DELETE FROM inbox_conversation c WHERE c.id = ANY(CAST(:left AS uuid[]))
                        AND NOT EXISTS (SELECT 1 FROM inbox_conversation_status cs WHERE cs.conversation_id = c.id)
                        """).param("left", left.stream().map(UUID::toString).toArray(String[]::new)).update();
    }

    /** @return the IDs of remote actors on a domain that sort after {@code after}, in order */
    public List<UUID> actorsOn(String domain, UUID after, int limit) {
        return jdbc.sql("SELECT id FROM actor WHERE NOT is_local AND domain = :domain AND id > :after ORDER BY id LIMIT :limit")
                .param("domain", domain).param("after", after).param("limit", limit).query(UUID.class).list();
    }

    public record Source(UUID id, UUID authorId, UUID threadId, boolean direct) {
    }

    private record Key(UUID owner, List<UUID> people) {
    }
}
