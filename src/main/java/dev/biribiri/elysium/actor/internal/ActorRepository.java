package dev.biribiri.elysium.actor.internal;

import dev.biribiri.elysium.actor.Actor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ActorRepository {

    private static final String SELECT_LOCAL = """
            SELECT a.id, a.uri, a.username, a.domain, a.inbox_uri, a.outbox_uri, a.shared_inbox_uri,
                   a.public_key_pem, a.created_at
            FROM actor a
            JOIN local_account l ON l.actor_id = a.id
            """;

    private static final RowMapper<Actor> ACTOR_MAPPER = (rs, rowNum) -> {
        var sharedInbox = rs.getString("shared_inbox_uri");
        return new Actor(
                rs.getObject("id", UUID.class),
                URI.create(rs.getString("uri")),
                rs.getString("username"),
                rs.getString("domain"),
                URI.create(rs.getString("inbox_uri")),
                URI.create(rs.getString("outbox_uri")),
                sharedInbox == null ? null : URI.create(sharedInbox),
                rs.getString("public_key_pem"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    };

    private final JdbcClient jdbc;

    ActorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Actor> findLocal(String username, String domain) {
        return jdbc.sql(SELECT_LOCAL + "WHERE lower(a.username) = lower(:username) AND a.domain = :domain")
                .param("username", username)
                .param("domain", domain)
                .query(ACTOR_MAPPER)
                .optional();
    }

    public Optional<Actor> findLocalByUri(URI uri) {
        return jdbc.sql(SELECT_LOCAL + "WHERE a.uri = :uri")
                .param("uri", uri.toString())
                .query(ACTOR_MAPPER)
                .optional();
    }

    public Optional<Actor> findLocalById(UUID id) {
        return jdbc.sql(SELECT_LOCAL + "WHERE a.id = :id")
                .param("id", id)
                .query(ACTOR_MAPPER)
                .optional();
    }

    public List<Actor> findLocalByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT_LOCAL + "WHERE a.id IN (:ids)").param("ids", ids).query(ACTOR_MAPPER).list();
    }

    public long countLocal() {
        return jdbc.sql("SELECT count(*) FROM local_account").query(Long.class).single();
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
                                           public_key_pem, created_at)
                        VALUES (:id, :uri, :username, :domain, :inboxUri, :outboxUri, :sharedInboxUri, :publicKeyPem,
                                :createdAt)
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
