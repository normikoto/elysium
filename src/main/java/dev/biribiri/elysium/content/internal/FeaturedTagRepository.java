package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.content.FeaturedTag;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class FeaturedTagRepository {

    /**
     * The statuses that count toward a featured tag, as Mastodon counts them: the account's own public and unlisted ones,
     * which anyone can see on its profile.
     */
    private static final String LISTED = """
            s.actor_id = f.account_id AND s.visibility IN ('public', 'unlisted') AND NOT s.local_only \
            AND s.reblog_of_id IS NULL""";

    private static final String SELECT = """
            SELECT f.id, f.name, u.uses, u.last_used FROM featured_tag f
            CROSS JOIN LATERAL (
                SELECT count(*) AS uses, max(s.published_at) AS last_used FROM status s
                JOIN status_tag t ON t.status_id = s.id AND t.name = f.name
                WHERE\s""" + LISTED + """
            ) u
            """;

    /**
     * How many of the account's newest statuses suggestions come from, so that an account with years of them doesn't read
     * them all.
     */
    private static final int SUGGESTED_FROM = 1000;

    private static final RowMapper<FeaturedTag> MAPPER = (rs, row) -> new FeaturedTag(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getLong("uses"),
            Optional.ofNullable(rs.getObject("last_used", OffsetDateTime.class)).map(OffsetDateTime::toInstant)
                    .orElse(null));

    private final JdbcClient jdbc;

    FeaturedTagRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Serializes features by one account, so that two can't both pass the limit.
     */
    public void lockAccount(UUID accountId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "featured tag\n" + accountId).query((rs, row) -> true).single();
    }

    public void insert(UUID accountId, String name) {
        jdbc.sql("""
                        INSERT INTO featured_tag (id, account_id, name) VALUES (:id, :account, :name)
                        ON CONFLICT (account_id, name) DO NOTHING
                        """)
                .param("id", Ids.timeOrdered(Instant.now()))
                .param("account", accountId)
                .param("name", name)
                .update();
    }

    public boolean delete(UUID accountId, UUID id) {
        return jdbc.sql("DELETE FROM featured_tag WHERE id = :id AND account_id = :account")
                .param("id", id)
                .param("account", accountId)
                .update() > 0;
    }

    public long count(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM featured_tag WHERE account_id = :account")
                .param("account", accountId)
                .query(Long.class)
                .single();
    }

    public Optional<FeaturedTag> find(UUID accountId, String name) {
        return jdbc.sql(SELECT + "WHERE f.account_id = :account AND f.name = :name")
                .param("account", accountId)
                .param("name", name)
                .query(MAPPER)
                .optional();
    }

    /**
     * Most used first, as on Mastodon.
     */
    public List<FeaturedTag> findAll(UUID accountId) {
        return jdbc.sql(SELECT + "WHERE f.account_id = :account ORDER BY u.uses DESC, f.name")
                .param("account", accountId)
                .query(MAPPER)
                .list();
    }

    public Set<String> findNames(UUID accountId, Collection<String> names) {
        if (names.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("SELECT name FROM featured_tag WHERE account_id = :account AND name IN (:names)")
                .param("account", accountId)
                .param("names", names)
                .query(String.class)
                .list());
    }

    /**
     * Tags the account has used in its recent public and unlisted statuses, and hasn't featured, the most recently used
     * first.
     */
    public List<String> findSuggestions(UUID accountId, int limit) {
        return jdbc.sql("""
                        SELECT t.name FROM (
                            SELECT s.id, s.published_at FROM status s
                            WHERE s.actor_id = :account AND s.visibility IN ('public', 'unlisted') AND NOT s.local_only
                            AND s.reblog_of_id IS NULL
                            ORDER BY s.id DESC LIMIT :from
                        ) s
                        JOIN status_tag t ON t.status_id = s.id
                        WHERE NOT EXISTS (SELECT 1 FROM featured_tag f WHERE f.account_id = :account AND f.name = t.name)
                        GROUP BY t.name
                        ORDER BY max(s.published_at) DESC, t.name
                        LIMIT :limit
                        """)
                .param("account", accountId)
                .param("from", SUGGESTED_FROM)
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    public void deleteExcept(UUID accountId, Collection<String> names) {
        jdbc.sql("DELETE FROM featured_tag WHERE account_id = :account AND NOT (name = ANY(:names))")
                .param("account", accountId)
                .param("names", names.toArray(String[]::new))
                .update();
    }

    /**
     * @return how many were deleted, up to {@code limit}
     */
    public int deleteForDomain(String domain, int limit) {
        return jdbc.sql("""
                        DELETE FROM featured_tag WHERE id IN (
                            SELECT f.id FROM featured_tag f JOIN actor a ON a.id = f.account_id
                            WHERE NOT a.is_local AND a.domain = :domain
                            LIMIT :limit
                        )
                        """)
                .param("domain", domain)
                .param("limit", limit)
                .update();
    }

    public void deleteForAccount(UUID accountId) {
        jdbc.sql("DELETE FROM featured_tag WHERE account_id = :account").param("account", accountId).update();
    }
}
