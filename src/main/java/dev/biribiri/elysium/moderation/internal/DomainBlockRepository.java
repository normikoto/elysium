package dev.biribiri.elysium.moderation.internal;

import dev.biribiri.elysium.moderation.DomainBlock;
import dev.biribiri.elysium.moderation.DomainBlockPage;
import org.springframework.jdbc.core.RowMapper;
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

    private static final String COLUMNS = "id, domain, private_comment, created_at, severity, reject_media";

    private static final RowMapper<DomainBlock> MAPPER = (rs, row) -> new DomainBlock(
            rs.getObject("id", UUID.class),
            rs.getString("domain"),
            rs.getString("private_comment"),
            rs.getTimestamp("created_at").toInstant(),
            DomainBlock.Severity.fromValue(rs.getString("severity")),
            rs.getBoolean("reject_media"));

    private final JdbcClient jdbc;

    DomainBlockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return whether it was saved, which it isn't if the domain already has a block
     */
    public boolean insert(DomainBlock block) {
        return jdbc.sql("""
                        INSERT INTO domain_block (id, domain, private_comment, created_at, severity, reject_media)
                        VALUES (:id, :domain, :comment, :createdAt, :severity, :rejectMedia)
                        ON CONFLICT (domain) DO NOTHING
                        """)
                .param("id", block.id())
                .param("domain", block.domain())
                .param("comment", block.privateComment())
                .param("createdAt", block.createdAt().atOffset(ZoneOffset.UTC))
                .param("severity", block.severity().value())
                .param("rejectMedia", block.rejectMedia())
                .update() == 1;
    }

    public Optional<DomainBlock> updateComment(UUID id, String comment) {
        return jdbc.sql("""
                        UPDATE domain_block SET private_comment = :comment WHERE id = :id
                        RETURNING %s
                        """.formatted(COLUMNS))
                .param("id", id)
                .param("comment", comment)
                .query(MAPPER)
                .optional();
    }

    public Optional<DomainBlock> update(UUID id, DomainBlock.Severity severity, boolean rejectMedia, String comment) {
        return jdbc.sql("""
                        UPDATE domain_block SET severity = :severity, reject_media = :rejectMedia, private_comment = :comment
                        WHERE id = :id
                        RETURNING %s
                        """.formatted(COLUMNS))
                .param("id", id)
                .param("severity", severity.value())
                .param("rejectMedia", rejectMedia)
                .param("comment", comment)
                .query(MAPPER)
                .optional();
    }

    /**
     * Like {@link #find}, locked until the transaction ends, so that a change to it can tell what it was.
     */
    public Optional<DomainBlock> findAndLock(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM domain_block WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    /**
     * @return the block that was removed, if there was one
     */
    public Optional<DomainBlock> deleteReturning(UUID id) {
        return jdbc.sql("DELETE FROM domain_block WHERE id = :id RETURNING " + COLUMNS)
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public boolean delete(UUID id) {
        return jdbc.sql("DELETE FROM domain_block WHERE id = :id").param("id", id).update() == 1;
    }

    public Optional<DomainBlock> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM domain_block WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    /**
     * @return the blocks on any of these domains, broadest first
     */
    public List<DomainBlock> findCovering(Collection<String> domains) {
        return jdbc.sql("""
                        SELECT %s FROM domain_block
                        WHERE domain = ANY(:domains)
                        ORDER BY length(domain)
                        """.formatted(COLUMNS))
                .param("domains", domains.toArray(String[]::new))
                .query(MAPPER)
                .list();
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

        var blocks = jdbc.sql(sql.toString()).params(parameters).query(MAPPER).list();
        return ascending ? blocks.reversed() : blocks;
    }

    /**
     * @return those of these domains that are suspended
     */
    public Set<String> findBlocked(Collection<String> domains) {
        return new HashSet<>(jdbc.sql("SELECT domain FROM domain_block WHERE domain = ANY(:domains) AND severity = 'suspend'")
                .param("domains", domains.toArray(String[]::new))
                .query(String.class)
                .list());
    }

    /**
     * @return those of these domains whose attachments are rejected, which includes those that are suspended
     */
    public Set<String> findMediaRejected(Collection<String> domains) {
        return new HashSet<>(jdbc.sql("""
                        SELECT domain FROM domain_block
                        WHERE domain = ANY(:domains) AND (severity = 'suspend' OR reject_media)
                        """)
                .param("domains", domains.toArray(String[]::new))
                .query(String.class)
                .list());
    }

}
