package dev.biribiri.elysium.content.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class PostingDefaultRepository {

    private final JdbcClient jdbc;

    PostingDefaultRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> findQuotePolicy(UUID actorId) {
        return jdbc.sql("SELECT quote_policy FROM posting_default WHERE actor_id = :actorId")
                .param("actorId", actorId).query(String.class).optional();
    }

    public void setQuotePolicy(UUID actorId, String policy) {
        jdbc.sql("""
                        INSERT INTO posting_default (actor_id, quote_policy) VALUES (:actorId, :policy)
                        ON CONFLICT (actor_id) DO UPDATE SET quote_policy = excluded.quote_policy
                        """)
                .param("actorId", actorId).param("policy", policy).update();
    }

    public Optional<String> findQuoteOthers(UUID actorId) {
        return jdbc.sql("SELECT quote_others FROM posting_default WHERE actor_id = :actorId")
                .param("actorId", actorId).query(String.class).optional();
    }

    /**
     * An account with no row yet gets the default policy, which is what it had.
     */
    public void setQuoteOthers(UUID actorId, String others) {
        jdbc.sql("""
                        INSERT INTO posting_default (actor_id, quote_policy, quote_others)
                        VALUES (:actorId, 'public', :others)
                        ON CONFLICT (actor_id) DO UPDATE SET quote_others = excluded.quote_others
                        """)
                .param("actorId", actorId).param("others", others).update();
    }

    public void removeFor(UUID actorId) {
        jdbc.sql("DELETE FROM posting_default WHERE actor_id = :actorId").param("actorId", actorId).update();
    }
}
