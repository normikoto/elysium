package dev.biribiri.elysium.content.internal;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.UUID;

/** Indexes the posts that can still count toward trends on upgrade. */
@Component
class V82__StatusLinkBackfill extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        var connection = context.getConnection();
        try (var select = connection.prepareStatement("""
                SELECT s.id, s.content, s.published_at FROM status s
                WHERE s.published_at >= (date_trunc('day', now() AT TIME ZONE 'UTC') - interval '6 days') AT TIME ZONE 'UTC'
                AND (s.published_at, s.id) < (?, ?)
                AND\s""" + StatusLinks.ELIGIBLE + " ORDER BY s.published_at DESC, s.id DESC LIMIT 500");
             var insert = connection.prepareStatement("""
                     INSERT INTO status_link (status_id, url) VALUES (?, ?) ON CONFLICT DO NOTHING
                     """)) {
            var publishedAt = OffsetDateTime.MAX;
            var id = new UUID(0, 0);
            while (true) {
                select.setObject(1, publishedAt);
                select.setObject(2, id);
                var posts = new ArrayList<Post>();
                try (var rows = select.executeQuery()) {
                    while (rows.next()) {
                        id = rows.getObject("id", UUID.class);
                        publishedAt = rows.getObject("published_at", OffsetDateTime.class);
                        posts.add(new Post(id, rows.getString("content")));
                    }
                }
                if (posts.isEmpty()) {
                    return;
                }
                for (var post : posts) {
                    for (var url : StatusLinks.from(post.content())) {
                        insert.setObject(1, post.id());
                        insert.setString(2, url);
                        insert.addBatch();
                    }
                }
                insert.executeBatch();
            }
        }
    }

    private record Post(UUID id, String content) {
    }
}
