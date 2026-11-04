package dev.biribiri.elysium.actor.internal;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ActorRepository {

    private static final String COLUMNS = """
            actor.id, actor.uri, actor.username, actor.domain, actor.inbox_uri, actor.outbox_uri, actor.shared_inbox_uri,
            actor.public_key_pem, actor.created_at, actor.updated_at
            """;

    private static final String SELECT_LOCAL = "SELECT " + COLUMNS + """
            FROM actor
            JOIN local_account ON local_account.actor_id = actor.id
            """;

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
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    };

    private final JdbcClient jdbc;

    ActorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Actor> findLocal(String username, String domain) {
        return jdbc.sql(SELECT_LOCAL + "WHERE lower(username) = lower(:username) AND domain = :domain")
                .param("username", username)
                .param("domain", domain)
                .query(ACTOR_MAPPER)
                .optional();
    }

    public Optional<Actor> findLocalByUri(URI uri) {
        return jdbc.sql(SELECT_LOCAL + "WHERE uri = :uri")
                .param("uri", uri.toString())
                .query(ACTOR_MAPPER)
                .optional();
    }

    public Optional<Actor> findByUri(URI uri) {
        return jdbc.sql("SELECT " + COLUMNS + "FROM actor WHERE uri = :uri")
                .param("uri", uri.toString())
                .query(ACTOR_MAPPER)
                .optional();
    }

    /**
     * Inserts an actor from another server, or replaces what's stored about it. Only the URI is looked at for this, so
     * another actor with the same username is an error.
     *
     * @throws org.springframework.dao.DuplicateKeyException if another actor on that server has the username
     */
    public Actor upsertRemote(RemoteActorProfile profile) {
        return jdbc.sql("""
                        INSERT INTO actor (id, uri, username, domain, inbox_uri, outbox_uri, shared_inbox_uri, public_key_pem)
                        VALUES (:id, :uri, :username, :domain, :inboxUri, :outboxUri, :sharedInboxUri, :publicKeyPem)
                        ON CONFLICT (uri) DO UPDATE SET
                            username = EXCLUDED.username,
                            inbox_uri = EXCLUDED.inbox_uri,
                            outbox_uri = EXCLUDED.outbox_uri,
                            shared_inbox_uri = EXCLUDED.shared_inbox_uri,
                            public_key_pem = EXCLUDED.public_key_pem,
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
                .query(ACTOR_MAPPER)
                .single();
    }

    private static String text(URI uri) {
        return uri == null ? null : uri.toString();
    }

    public Optional<Actor> findLocalById(UUID id) {
        return jdbc.sql(SELECT_LOCAL + "WHERE id = :id")
                .param("id", id)
                .query(ACTOR_MAPPER)
                .optional();
    }

    public List<Actor> findLocalByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT_LOCAL + "WHERE id IN (:ids)").param("ids", ids).query(ACTOR_MAPPER).list();
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

    public long countLocal() {
        return jdbc.sql("SELECT count(*) FROM local_account").query(Long.class).single();
    }

    public boolean hasLocalAccounts() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM local_account)").query(Boolean.class).single();
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
                                           public_key_pem, created_at, updated_at)
                        VALUES (:id, :uri, :username, :domain, :inboxUri, :outboxUri, :sharedInboxUri, :publicKeyPem,
                                :createdAt, :updatedAt)
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
