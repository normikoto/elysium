package dev.biribiri.elysium.actor.internal;

import dev.biribiri.elysium.actor.Actor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.Optional;

@Repository
public class ActorRepository {

    private static final RowMapper<Actor> ACTOR_MAPPER = (rs, rowNum) -> {
        var sharedInbox = rs.getString("shared_inbox_uri");
        return new Actor(
                rs.getObject("id", java.util.UUID.class),
                URI.create(rs.getString("uri")),
                rs.getString("username"),
                rs.getString("domain"),
                URI.create(rs.getString("inbox_uri")),
                URI.create(rs.getString("outbox_uri")),
                sharedInbox == null ? null : URI.create(sharedInbox),
                rs.getString("public_key_pem"));
    };

    private final JdbcClient jdbc;

    ActorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Actor> findLocal(String username, String domain) {
        return jdbc.sql("""
                        SELECT a.id, a.uri, a.username, a.domain, a.inbox_uri, a.outbox_uri, a.shared_inbox_uri,
                               a.public_key_pem
                        FROM actor a
                        JOIN local_account l ON l.actor_id = a.id
                        WHERE lower(a.username) = lower(:username) AND a.domain = :domain
                        """)
                .param("username", username)
                .param("domain", domain)
                .query(ACTOR_MAPPER)
                .optional();
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
                                           public_key_pem)
                        VALUES (:id, :uri, :username, :domain, :inboxUri, :outboxUri, :sharedInboxUri, :publicKeyPem)
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
