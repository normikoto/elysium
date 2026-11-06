package dev.biribiri.elysium.actor.internal;

import dev.biribiri.elysium.actor.AccountFilter;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ActorRepository {

    private static final String COLUMNS = """
            actor.id, actor.uri, actor.username, actor.domain, actor.inbox_uri, actor.outbox_uri, actor.shared_inbox_uri,
            actor.public_key_pem, actor.created_at, actor.updated_at, actor.suspended_at, actor.deleted_at
            """;

    private static final String ACTIVE = "actor.suspended_at IS NULL AND actor.deleted_at IS NULL";

    /**
     * Local actors that are neither suspended nor deleted. This is what nearly everything should use, so that something new
     * can't serve a suspended account by forgetting to check.
     */
    private static final String SELECT_ACTIVE_LOCAL = "SELECT " + COLUMNS + """
            FROM actor
            JOIN local_account ON local_account.actor_id = actor.id
            WHERE
            """ + " " + ACTIVE + " AND ";

    /**
     * Local actors in any state. A row in {@code local_account} isn't needed, because one that was deleted long enough ago
     * has lost it.
     */
    private static final String SELECT_ANY_LOCAL = "SELECT " + COLUMNS + "FROM actor WHERE is_local AND ";

    private static final RowMapper<Actor> ACTOR_MAPPER = (rs, rowNum) -> {
        var outbox = rs.getString("outbox_uri");
        var sharedInbox = rs.getString("shared_inbox_uri");
        return new Actor(
                rs.getObject("id", UUID.class),
                URI.create(rs.getString("uri")),
                rs.getString("username"),
                rs.getString("domain"),
                URI.create(rs.getString("inbox_uri")),
                outbox == null ? null : URI.create(outbox),
                sharedInbox == null ? null : URI.create(sharedInbox),
                rs.getString("public_key_pem"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                instant(rs.getObject("suspended_at", OffsetDateTime.class)),
                instant(rs.getObject("deleted_at", OffsetDateTime.class)));
    };

    private static Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    private final JdbcClient jdbc;

    ActorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Actor> findLocal(String username, String domain) {
        return jdbc.sql(SELECT_ACTIVE_LOCAL + "lower(username) = lower(:username) AND domain = :domain AND is_local")
                .param("username", username)
                .param("domain", domain)
                .query(ACTOR_MAPPER)
                .optional();
    }

    public Optional<Actor> findLocalByUri(URI uri) {
        return jdbc.sql(SELECT_ACTIVE_LOCAL + "uri = :uri")
                .param("uri", uri.toString())
                .query(ACTOR_MAPPER)
                .optional();
    }

    public Optional<Actor> findAnyLocal(String username, String domain) {
        return jdbc.sql(SELECT_ANY_LOCAL + "lower(username) = lower(:username) AND domain = :domain")
                .param("username", username)
                .param("domain", domain)
                .query(ACTOR_MAPPER)
                .optional();
    }

    public Optional<Actor> findAnyLocalByUri(URI uri) {
        return jdbc.sql(SELECT_ANY_LOCAL + "uri = :uri").param("uri", uri.toString()).query(ACTOR_MAPPER).optional();
    }

    public Optional<Actor> findAnyLocalById(UUID id) {
        return jdbc.sql(SELECT_ANY_LOCAL + "id = :id").param("id", id).query(ACTOR_MAPPER).optional();
    }

    /**
     * Finds a local or remote actor in any state.
     */
    public Optional<Actor> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + "FROM actor WHERE id = :id").param("id", id).query(ACTOR_MAPPER).optional();
    }

    public boolean isSuspended(URI uri) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM actor WHERE uri = :uri AND suspended_at IS NOT NULL)")
                .param("uri", uri.toString())
                .query(Boolean.class)
                .single();
    }

    /**
     * Finds which of these inboxes only suspended actors use. Some servers give all of their actors the same inbox, so one
     * that an active actor also uses doesn't count.
     */
    public List<String> findSuspendedInboxes(Collection<String> inboxes) {
        if (inboxes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT DISTINCT s.inbox_uri FROM actor s
                        WHERE s.suspended_at IS NOT NULL
                          AND s.inbox_uri IN (:inboxes)
                          AND NOT EXISTS (
                              SELECT 1 FROM actor o
                              WHERE o.suspended_at IS NULL
                                AND o.deleted_at IS NULL
                                AND (o.inbox_uri = s.inbox_uri OR o.shared_inbox_uri = s.inbox_uri))
                        """)
                .param("inboxes", inboxes)
                .query(String.class)
                .list();
    }

    /**
     * @return when it was suspended, or empty if there's nothing to do, because it doesn't exist, it's already suspended, or
     * it's been deleted
     */
    public Optional<Instant> markSuspended(UUID id) {
        return jdbc.sql("""
                        UPDATE actor SET suspended_at = now()
                        WHERE id = :id AND suspended_at IS NULL AND deleted_at IS NULL
                        RETURNING suspended_at
                        """)
                .param("id", id)
                .query((rs, rowNum) -> rs.getObject("suspended_at", OffsetDateTime.class).toInstant())
                .optional();
    }

    /**
     * @return when its suspension ended, or empty if it isn't suspended
     */
    public Optional<Instant> markUnsuspended(UUID id) {
        return jdbc.sql("""
                        UPDATE actor SET suspended_at = NULL
                        WHERE id = :id AND suspended_at IS NOT NULL AND deleted_at IS NULL
                        RETURNING now() AS at
                        """)
                .param("id", id)
                .query((rs, rowNum) -> rs.getObject("at", OffsetDateTime.class).toInstant())
                .optional();
    }

    /**
     * @return whether this deleted it, which it won't have if it isn't local, or was deleted already
     */
    public boolean markDeleted(UUID id) {
        return jdbc.sql("UPDATE actor SET deleted_at = now() WHERE id = :id AND is_local AND deleted_at IS NULL")
                .param("id", id)
                .update() > 0;
    }

    /**
     * Removes the keys of actors deleted before this time. Nothing is left that could sign as them, and the row only reserves
     * the username and URI from then on.
     */
    @Transactional
    public void removeKeysOfDeleted(Instant before) {
        var time = before.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                        DELETE FROM local_account USING actor
                        WHERE local_account.actor_id = actor.id AND actor.deleted_at < :before
                        """)
                .param("before", time)
                .update();
        jdbc.sql("UPDATE actor SET public_key_pem = NULL WHERE deleted_at < :before AND public_key_pem IS NOT NULL")
                .param("before", time)
                .update();
    }

    public List<Actor> list(AccountFilter filter) {
        var sql = new StringBuilder("SELECT ").append(COLUMNS).append("FROM actor WHERE deleted_at IS NULL");
        var parameters = new HashMap<String, Object>();
        if (filter.local() != null) {
            sql.append(filter.local() ? " AND is_local" : " AND NOT is_local");
        }
        if (filter.suspendedOnly()) {
            sql.append(" AND suspended_at IS NOT NULL");
        }
        if (filter.usernamePrefix() != null) {
            sql.append(" AND lower(username) LIKE :prefix ESCAPE '\\'");
            parameters.put("prefix", escapeLike(filter.usernamePrefix().toLowerCase(Locale.ROOT)) + "%");
        }
        if (filter.domain() != null) {
            sql.append(" AND lower(domain) = :domain");
            parameters.put("domain", filter.domain().toLowerCase(Locale.ROOT));
        }
        if (filter.maxId() != null) {
            sql.append(" AND id < :maxId");
            parameters.put("maxId", filter.maxId());
        }
        // Predicates are only added when needed, because PostgreSQL can't determine the type of a null parameter.
        var ascending = filter.minId() != null;
        if (ascending) {
            sql.append(" AND id > :minId");
            parameters.put("minId", filter.minId());
        } else if (filter.sinceId() != null) {
            sql.append(" AND id > :sinceId");
            parameters.put("sinceId", filter.sinceId());
        }
        sql.append(ascending ? " ORDER BY id ASC" : " ORDER BY id DESC").append(" LIMIT :limit");
        parameters.put("limit", filter.limit());
        var found = jdbc.sql(sql.toString()).params(parameters).query(ACTOR_MAPPER).list();
        return ascending ? found.reversed() : found;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public Optional<Actor> findByUri(URI uri) {
        return jdbc.sql("SELECT " + COLUMNS + "FROM actor WHERE uri = :uri")
                .param("uri", uri.toString())
                .query(ACTOR_MAPPER)
                .optional();
    }

    /**
     * Inserts an actor from another server, or replaces what's stored about it. Only the URI identifies it, so another actor
     * on that server can have the same username.
     */
    public Actor upsertRemote(RemoteActorProfile profile) {
        return jdbc.sql("""
                        INSERT INTO actor (id, uri, username, domain, inbox_uri, outbox_uri, shared_inbox_uri, public_key_pem,
                                           followers_uri)
                        VALUES (:id, :uri, :username, :domain, :inboxUri, :outboxUri, :sharedInboxUri, :publicKeyPem,
                                :followersUri)
                        ON CONFLICT (uri) DO UPDATE SET
                            username = EXCLUDED.username,
                            inbox_uri = EXCLUDED.inbox_uri,
                            outbox_uri = EXCLUDED.outbox_uri,
                            shared_inbox_uri = EXCLUDED.shared_inbox_uri,
                            public_key_pem = EXCLUDED.public_key_pem,
                            followers_uri = EXCLUDED.followers_uri,
                            updated_at = now()
                        RETURNING\s""" + COLUMNS)
                .param("id", UUID.randomUUID())
                .param("uri", profile.uri().toString())
                .param("username", profile.username())
                .param("domain", profile.domain())
                .param("inboxUri", profile.inboxUri().toString())
                .param("outboxUri", text(profile.outboxUri()), Types.VARCHAR)
                .param("sharedInboxUri", text(profile.sharedInboxUri()), Types.VARCHAR)
                .param("publicKeyPem", profile.publicKeyPem(), Types.VARCHAR)
                .param("followersUri", text(profile.followersUri()), Types.VARCHAR)
                .query(ACTOR_MAPPER)
                .single();
    }

    /**
     * @return a remote actor's followers collection, if it has one stored
     */
    public Optional<URI> findFollowersUri(UUID id) {
        return jdbc.sql("SELECT followers_uri FROM actor WHERE id = :id AND followers_uri IS NOT NULL")
                .param("id", id)
                .query((rs, row) -> URI.create(rs.getString(1)))
                .optional();
    }

    private static String text(URI uri) {
        return uri == null ? null : uri.toString();
    }

    public Optional<Actor> findLocalById(UUID id) {
        return jdbc.sql(SELECT_ACTIVE_LOCAL + "id = :id")
                .param("id", id)
                .query(ACTOR_MAPPER)
                .optional();
    }

    public List<Actor> findLocalByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT_ACTIVE_LOCAL + "id IN (:ids)").param("ids", ids).query(ACTOR_MAPPER).list();
    }

    /**
     * Takes the URIs as one array, since they can come from another server, and there can be more than a query can have
     * parameters.
     */
    public List<Actor> findLocalByUris(Collection<String> uris) {
        if (uris.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT_ACTIVE_LOCAL + "uri = ANY(:uris)")
                .param("uris", uris.toArray(String[]::new))
                .query(ACTOR_MAPPER)
                .list();
    }

    /**
     * Finds the remote actor with this username on this domain, refreshed most recently. Usernames aren't unique for remote
     * actors, because a server can give one to a new actor while an old copy of the last one to have it is stored here.
     *
     * @param domain in lowercase, as remote actors' domains are stored, so that the domain's index is used
     */
    public Optional<Actor> findRemoteByAcct(String username, String domain) {
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM actor
                        WHERE NOT is_local AND domain = :domain AND lower(username) = lower(:username)
                        ORDER BY updated_at DESC
                        LIMIT 1
                        """)
                .param("username", username)
                .param("domain", domain)
                .query(ACTOR_MAPPER)
                .optional();
    }

    /**
     * Finds local and remote actors in any state.
     */
    public List<Actor> findByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM actor WHERE id IN (:ids)").param("ids", ids).query(ACTOR_MAPPER).list();
    }

    /**
     * @return the local accounts among these that hide what's in their followers and following collections
     */
    public List<UUID> findHidingCollections(Collection<UUID> actorIds) {
        if (actorIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT actor_id FROM local_account WHERE hide_collections AND actor_id IN (:ids)")
                .param("ids", actorIds)
                .query(UUID.class)
                .list();
    }

    /**
     * @return whether the account exists to be changed
     */
    public boolean setHidesCollections(UUID actorId, boolean hide) {
        return jdbc.sql("UPDATE local_account SET hide_collections = :hide WHERE actor_id = :actorId")
                .param("hide", hide)
                .param("actorId", actorId)
                .update() > 0;
    }

    /**
     * Reads an actor's private key. Actor queries leave this column out, so the key can't end up in an {@link Actor}.
     */
    public Optional<String> findPrivateKeyPem(UUID actorId) {
        return jdbc.sql("SELECT private_key_pem FROM local_account WHERE actor_id = :actorId")
                .param("actorId", actorId)
                .query(String.class)
                .optional();
    }

    /**
     * Counts the accounts that the public can see.
     */
    public long countLocal() {
        return jdbc.sql("""
                        SELECT count(*) FROM local_account
                        JOIN actor ON actor.id = local_account.actor_id
                        WHERE\s""" + ACTIVE)
                .query(Long.class)
                .single();
    }

    /**
     * Counts the accounts that exist, which includes suspended ones, because they can come back.
     */
    public long countExistingLocal() {
        return jdbc.sql("""
                        SELECT count(*) FROM local_account
                        JOIN actor ON actor.id = local_account.actor_id
                        WHERE actor.deleted_at IS NULL
                        """)
                .query(Long.class)
                .single();
    }

    public boolean hasLocalAccounts() {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM local_account
                            JOIN actor ON actor.id = local_account.actor_id
                            WHERE actor.deleted_at IS NULL)
                        """)
                .query(Boolean.class)
                .single();
    }

    /**
     * Inserts a local actor unless one with the same identity already exists.
     *
     * @return whether the actor was created
     */
    @Transactional
    public boolean createLocalIfAbsent(Actor actor, String privateKeyPem) {
        var inserted = jdbc.sql("""
                        INSERT INTO actor (id, uri, username, domain, inbox_uri, outbox_uri, shared_inbox_uri,
                                           public_key_pem, created_at, updated_at, is_local)
                        VALUES (:id, :uri, :username, :domain, :inboxUri, :outboxUri, :sharedInboxUri, :publicKeyPem,
                                :createdAt, :updatedAt, TRUE)
                        ON CONFLICT DO NOTHING
                        """)
                .param("id", actor.id())
                .param("uri", actor.uri().toString())
                .param("username", actor.username())
                .param("domain", actor.domain())
                .param("inboxUri", actor.inboxUri().toString())
                .param("outboxUri", actor.outboxUri().toString())
                .param("sharedInboxUri", actor.sharedInboxUri() == null ? null : actor.sharedInboxUri().toString(),
                        java.sql.Types.VARCHAR)
                .param("publicKeyPem", actor.publicKeyPem())
                .param("createdAt", actor.createdAt().atOffset(ZoneOffset.UTC))
                .param("updatedAt", actor.updatedAt().atOffset(ZoneOffset.UTC))
                .update();
        if (inserted == 0) {
            return false;
        }
        jdbc.sql("INSERT INTO local_account (actor_id, private_key_pem) VALUES (:actorId, :privateKeyPem)")
                .param("actorId", actor.id())
                .param("privateKeyPem", privateKeyPem)
                .update();
        return true;
    }
}
