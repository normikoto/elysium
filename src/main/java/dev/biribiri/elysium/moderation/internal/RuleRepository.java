package dev.biribiri.elysium.moderation.internal;

import dev.biribiri.elysium.moderation.Rule;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RuleRepository {

    private static final String COLUMNS = "id, text, hint, position, created_at, updated_at";

    private static final RowMapper<Rule> MAPPER = (rs, row) -> new Rule(
            rs.getObject("id", UUID.class),
            rs.getString("text"),
            rs.getString("hint"),
            rs.getInt("position"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcClient jdbc;

    RuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Rule> findAll() {
        return jdbc.sql("SELECT %s FROM instance_rule ORDER BY position, id".formatted(COLUMNS))
                .query(MAPPER)
                .list();
    }

    /**
     * Locks every rule, so that a reorder sees the positions it's changing as they stay until it commits. There are only
     * ever a few.
     */
    public List<Rule> findAllAndLock() {
        return jdbc.sql("SELECT %s FROM instance_rule ORDER BY position, id FOR UPDATE".formatted(COLUMNS))
                .query(MAPPER)
                .list();
    }

    /**
     * Puts it after every other rule.
     */
    public Rule insertLast(UUID id, String text, String hint, Instant now) {
        return jdbc.sql("""
                        INSERT INTO instance_rule (id, text, hint, position, created_at, updated_at)
                        SELECT :id, :text, :hint, COALESCE(MAX(position) + 1, 0), :now, :now FROM instance_rule
                        RETURNING %s
                        """.formatted(COLUMNS))
                .param("id", id)
                .param("text", text)
                .param("hint", hint)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query(MAPPER)
                .single();
    }

    public Optional<Rule> update(UUID id, String text, String hint, Instant now) {
        return jdbc.sql("""
                        UPDATE instance_rule SET text = :text, hint = :hint, updated_at = :now WHERE id = :id
                        RETURNING %s
                        """.formatted(COLUMNS))
                .param("id", id)
                .param("text", text)
                .param("hint", hint)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query(MAPPER)
                .optional();
    }

    public void updatePosition(UUID id, int position) {
        jdbc.sql("UPDATE instance_rule SET position = :position WHERE id = :id")
                .param("id", id)
                .param("position", position)
                .update();
    }

    public boolean delete(UUID id) {
        return jdbc.sql("DELETE FROM instance_rule WHERE id = :id").param("id", id).update() == 1;
    }
}
