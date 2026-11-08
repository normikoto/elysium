package dev.biribiri.elysium.moderation.internal;

import dev.biribiri.elysium.moderation.DomainBlock;
import dev.biribiri.elysium.moderation.DomainBlockPage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class DomainBlockRepository {

    private static final String COLUMNS = "id, domain, private_comment, created_at";

    private final JdbcClient jdbc;

    DomainBlockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return whether it was saved, which it isn't if the domain already has a block
     */
    public boolean insert(DomainBlock block) {
        return jdbc.sql("""
                        INSERT INTO domain_block (id, domain, private_comment, created_at)
                        VALUES (:id, :domain, :comment, :createdAt)
                        ON CONFLICT (domain) DO NOTHING
                        """)
                .param("id", block.id())
                .param("domain", block.domain())
                .param("comment", block.privateComment())
                .param("createdAt", block.createdAt().atOffset(ZoneOffset.UTC))
                .update() == 1;
    }

    public Optional<DomainBlock> updateComment(UUID id, String comment) {
        return jdbc.sql("""
                        UPDATE domain_block SET private_comment = :comment WHERE id = :id
                        RETURNING %s
                        """.formatted(COLUMNS))
                .param("id", id)
                .param("comment", comment)
                .query(DomainBlock.class)
                .optional();
    }

    public boolean delete(UUID id) {
        return jdbc.sql("DELETE FROM domain_block WHERE id = :id").param("id", id).update() == 1;
    }

    public Optional<DomainBlock> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM domain_block WHERE id = :id")
                .param("id", id)
                .query(DomainBlock.class)
                .optional();
    }

    /**
     * @return the broadest block on any of these domains
     */
    public Optional<DomainBlock> findBroadest(Collection<String> domains) {
        return jdbc.sql("""
                        SELECT %s FROM domain_block
                        WHERE domain = ANY(:domains)
                        ORDER BY length(domain)
                        LIMIT 1
                        """.formatted(COLUMNS))
                .param("domains", domains.toArray(String[]::new))
                .query(DomainBlock.class)
                .optional();
    }

    public List<DomainBlock> findPage(DomainBlockPage page) {
        var sql = new StringBuilder("SELECT " + COLUMNS + " FROM domain_block WHERE TRUE");
        var parameters = new HashMap<String, Object>();
        if (page.maxId() != null) {
            sql.append(" AND id < :maxId");
            parameters.put("maxId", page.maxId());
        }
        // Predicates are only added when needed, because PostgreSQL can't determine the type of a null parameter.
        var ascending = page.minId() != null;
        var after = ascending ? page.minId() : page.sinceId();
        if (after != null) {
            sql.append(" AND id > :after");
            parameters.put("after", after);
        }
        sql.append(ascending ? " ORDER BY id ASC" : " ORDER BY id DESC").append(" LIMIT :limit");
        parameters.put("limit", page.limit());

        var blocks = jdbc.sql(sql.toString()).params(parameters).query(DomainBlock.class).list();
        return ascending ? blocks.reversed() : blocks;
    }

    /**
     * @return those of these domains that have a block
     */
    public Set<String> findBlocked(Collection<String> domains) {
        return new HashSet<>(jdbc.sql("SELECT domain FROM domain_block WHERE domain = ANY(:domains)")
                .param("domains", domains.toArray(String[]::new))
                .query(String.class)
                .list());
    }

}
