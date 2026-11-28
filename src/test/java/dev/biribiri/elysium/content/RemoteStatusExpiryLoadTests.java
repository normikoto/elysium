package dev.biribiri.elysium.content;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.internal.RemoteStatusExpiryRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures how long a batch of {@link RemoteStatusExpiry} holds the ID assignment lock, which every status being stored
 * waits for, and how long storing a status from another server takes meanwhile. Run with {@code ./gradlew loadTest}, which
 * needs Docker. {@code ./gradlew test} leaves it out, because seeding takes a while and its numbers are what matter.
 *
 * <p>The numbers depend on the machine, and the test database runs with {@code fsync=off}, so commits cost less than on a
 * real disk. They're for comparing batch sizes and runs.
 */
@Tag("load")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://elysium.example", "elysium.jobs.worker-enabled=false",
        "elysium.statuses.remote-retention=30d"})
class RemoteStatusExpiryLoadTests {

    private static final int AUTHORS = 1_000;
    private static final int STATUSES = 100_000;
    private static final int BATCHES = 20;
    private static final List<Integer> BATCH_SIZES = List.of(500, 250, 100);
    private static final Duration BASELINE = Duration.ofSeconds(5);

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;
    @Autowired
    private RemoteStatuses remoteStatuses;
    @Autowired
    private RemoteStatusExpiryRepository repository;
    @Autowired
    private List<StatusReferences> references;
    @Autowired
    private TransactionTemplate transactions;

    @Test
    void measuresHowLongABatchHoldsTheLock() throws Exception {
        var authors = seed();
        var before = Instant.now().plus(Duration.ofDays(1));
        var conditions = references.stream()
                .map(reference -> "(" + reference.refersTo("s.id") + ")")
                .collect(Collectors.joining("\nOR "));

        System.out.println();
        System.out.println(explainCascade(conditions, before));

        var baseline = storeWhile(authors, () -> sleep(BASELINE));
        System.out.printf("%nStoring a status, nothing else running: %s%n", summary(baseline));

        UUID after = null;
        for (var size : BATCH_SIZES) {
            var held = new ArrayList<Long>();
            var removed = new ArrayList<Integer>();
            var cursor = new UUID[]{after};
            var stored = storeWhile(authors, () -> {
                for (var i = 0; i < BATCHES; i++) {
                    var end = repository.findWindowEnd(before, cursor[0], size);
                    var expired = repository.findExpired(conditions, before, cursor[0], end.orElse(null));
                    var start = System.nanoTime();
                    removed.add(repository.remove(expired, conditions, before));
                    held.add(System.nanoTime() - start);
                    cursor[0] = end.orElseThrow();
                    // As a run does: the next window's queries run without the lock.
                }
            });
            after = cursor[0];
            System.out.printf("%nBatches of %d (removed %s on average): lock held %s%n", size,
                    removed.stream().mapToInt(Integer::intValue).average().orElse(0), summary(held));
            System.out.printf("  Storing a status meanwhile: %s%n", summary(stored));
        }
        assertThat(after).isNotNull();
    }

    /**
     * Where a full batch's time goes, from one that's rolled back: the delete and each cascade it sets off.
     */
    private String explainCascade(String conditions, Instant before) {
        var end = repository.findWindowEnd(before, null, BATCH_SIZES.getFirst()).orElseThrow();
        var ids = repository.findExpired(conditions, before, null, end).stream().map(UUID::toString)
                .toArray(String[]::new);
        return transactions.execute(status -> {
            status.setRollbackOnly();
            var plan = jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) DELETE FROM status WHERE id = ANY(CAST(:ids AS uuid[]))")
                    .param("ids", ids).query(String.class).list();
            return "Deleting " + ids.length + " statuses, without the keep checks:\n  " + String.join("\n  ", plan);
        });
    }

    /**
     * Stores statuses from another server, one after another, for as long as {@code work} runs.
     *
     * @return how long each took, in nanoseconds
     */
    private List<Long> storeWhile(List<Actor> authors, Runnable work) throws InterruptedException {
        var durations = Collections.synchronizedList(new ArrayList<Long>());
        var running = new AtomicBoolean(true);
        var storer = Thread.ofPlatform().start(() -> {
            var n = 0;
            while (running.get()) {
                var author = authors.get(n++ % authors.size());
                var mentioned = authors.get(n % authors.size());
                var start = System.nanoTime();
                remoteStatuses.storeRemote(new RemoteStatus(URI.create(author.uri() + "/live/" + UUID.randomUUID()),
                        null, author.id(), "<p>Live #tag" + n % 50 + "</p>", null, null, Visibility.PUBLIC, false, null,
                        Instant.now(), null, Map.of(mentioned.id(), false), List.of("tag" + n % 50), List.of(), null,
                        null, null)).orElseThrow();
                durations.add(System.nanoTime() - start);
            }
        });
        try {
            work.run();
        } finally {
            running.set(false);
            storer.join();
        }
        return durations;
    }

    private List<Actor> seed() {
        var started = System.nanoTime();
        var authors = new ArrayList<Actor>();
        for (var i = 0; i < AUTHORS; i++) {
            var host = "expiry-load-" + i + ".example";
            var uri = URI.create("https://" + host + "/users/r");
            authors.add(actors.upsertRemote(
                    new RemoteActorProfile(uri, "r", host, URI.create(uri + "/inbox"), null, null, null, null)));
        }
        var locals = new ArrayList<UUID>();
        for (var i = 0; i < 20; i++) {
            locals.add(actors.createLocalIfAbsent("load" + i, actor -> {
            }).orElseThrow().id());
        }
        // One statement, so that uuidv7() gives them IDs in order, as they'd have been stored in.
        jdbc.sql("""
                DO $$
                DECLARE authors_count INTEGER;
                BEGIN
                    CREATE TEMP TABLE seed_author ON COMMIT DROP AS
                        SELECT row_number() OVER (ORDER BY id) AS k, id, uri FROM actor
                        WHERE NOT is_local AND domain LIKE 'expiry-load-%%';
                    SELECT count(*) INTO authors_count FROM seed_author;
                    CREATE TEMP TABLE seed_status ON COMMIT DROP AS
                        SELECT n, uuidv7() AS id FROM generate_series(1, %1$d) n;
                    CREATE TEMP TABLE seed_local ON COMMIT DROP AS
                        SELECT row_number() OVER (ORDER BY id) AS k, id FROM actor WHERE is_local;

                    -- Threads of five, each replying to the one before. One in four has a reply from here, which
                    -- keeps the thread, as a reply from here would.
                    INSERT INTO status (id, uri, actor_id, text, content, visibility, published_at, thread_id, reply,
                                        in_reply_to_id)
                    SELECT s.id, CASE WHEN s.n %% 20 = 10 THEN 'https://elysium.example/load/' ELSE a.uri || '/statuses/' END || s.n,
                           CASE WHEN s.n %% 20 = 10 THEN l.id ELSE a.id END, 'From afar #tag', '<p>From afar</p>',
                           'public', now(), first_value(s.id) OVER t, (s.n - 1) %% 5 <> 0, lag(s.id) OVER t
                    FROM seed_status s JOIN seed_author a ON a.k = 1 + s.n %% authors_count
                    JOIN seed_local l ON l.k = 1 + s.n %% 20
                    WINDOW t AS (PARTITION BY (s.n - 1) / 5 ORDER BY s.n);
                    INSERT INTO status_thread_alias (uri, thread_id, actor_id)
                    SELECT st.uri, st.thread_id, st.actor_id FROM status st JOIN seed_status s ON s.id = st.id;

                    INSERT INTO status_tag (status_id, name)
                    SELECT id, 'tag' || n %% 500 FROM seed_status
                    UNION ALL SELECT id, 'other' || n %% 37 FROM seed_status;
                    INSERT INTO status_mention (status_id, actor_id)
                    SELECT s.id, a.id FROM seed_status s JOIN seed_author a ON a.k = 1 + (s.n + 7) %% authors_count;
                    -- Up to three favourites each, from other servers.
                    INSERT INTO status_like (id, actor_id, status_id, activity_uri, activity_at)
                    SELECT gen_random_uuid(), a.id, s.id, a.uri || '/likes/' || s.n || '/' || l, now()
                    FROM seed_status s CROSS JOIN generate_series(1, 3) l
                    JOIN seed_author a ON a.k = 1 + (s.n + l * 11) %% authors_count
                    WHERE l <= s.n %% 4;
                    -- A tenth are boosted twice from other servers.
                    INSERT INTO status (id, uri, actor_id, text, content, visibility, published_at, reblog_of_id)
                    SELECT uuidv7(), a.uri || '/boosts/' || s.n || '/' || b, a.id, '', '', 'public', now(),
                           s.id
                    FROM seed_status s CROSS JOIN generate_series(1, 2) b
                    JOIN seed_author a ON a.k = 1 + (s.n + b * 13) %% authors_count
                    WHERE s.n %% 10 = 0;
                    INSERT INTO media_attachment (id, account_id, status_id, type, remote_url)
                    SELECT gen_random_uuid(), st.actor_id, s.id, 'image', 'https://media.example/' || s.n
                    FROM seed_status s JOIN status st ON st.id = s.id WHERE s.n %% 5 = 0;
                    INSERT INTO status_link (status_id, url)
                    SELECT id, 'https://news.example/' || n %% 1000 FROM seed_status WHERE n %% 10 = 0;
                    INSERT INTO status_edit (status_id, text, content, spoiler_text, sensitive, created_at)
                    SELECT id, 'before', '<p>before</p>', '', false, now() FROM seed_status WHERE n %% 20 = 0;
                    -- A twentieth are kept, for a favourite from here.
                    INSERT INTO status_like (id, actor_id, status_id, activity_uri, activity_at)
                    SELECT gen_random_uuid(), l.id, s.id, 'https://elysium.example/likes/' || s.n, now()
                    FROM seed_status s JOIN seed_local l ON l.k = 1 + s.n %% 20 WHERE s.n %% 20 = 3;
                END
                $$
                """.formatted(STATUSES)).update();
        jdbc.sql("ANALYZE").update();
        System.out.printf("%nSeeded %d statuses from %d accounts on other servers in %s%n",
                jdbc.sql("SELECT count(*) FROM status").query(Long.class).single(), AUTHORS,
                Duration.ofNanos(System.nanoTime() - started));
        return authors;
    }

    private static String summary(List<Long> nanos) {
        var sorted = nanos.stream().sorted().toList();
        if (sorted.isEmpty()) return "none";
        return "%d, median %s, p95 %s, max %s".formatted(sorted.size(), millis(sorted.get(sorted.size() / 2)),
                millis(sorted.get((int) Math.min(sorted.size() - 1, Math.ceil(sorted.size() * 0.95) - 1))),
                millis(sorted.getLast()));
    }

    private static String millis(long nanos) {
        return "%.1f ms".formatted(nanos / 1e6);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
