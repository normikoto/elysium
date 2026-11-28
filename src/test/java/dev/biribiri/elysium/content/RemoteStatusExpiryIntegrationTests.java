package dev.biribiri.elysium.content;

import dev.biribiri.elysium.TestAccounts;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.moderation.ReportCategory;
import dev.biribiri.elysium.moderation.Reports;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The job is off here, so that tests run it themselves, as if it were later, rather than wait for statuses to age.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://elysium.example", "elysium.jobs.worker-enabled=false",
        "elysium.statuses.remote-retention=30d"})
class RemoteStatusExpiryIntegrationTests {

    private static final Duration LATER = Duration.ofDays(31);

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;
    @Autowired
    private StatusService statuses;
    @Autowired
    private RemoteStatuses remoteStatuses;
    @Autowired
    private LikeService likes;
    @Autowired
    private BookmarkService bookmarks;
    @Autowired
    private BoostService boosts;
    @Autowired
    private Reports reports;
    @Autowired
    private PollService polls;
    @Autowired
    private StatusPinService pins;
    @Autowired
    private RemoteStatusExpiry expiry;

    @BeforeEach
    void setUp() {
        TestAccounts.makeSureAnAccountExists(actors);
        jdbc.sql("UPDATE remote_status_expiry SET scanned_to = NULL, pass_started_at = NULL").update();
    }

    @Test
    void removesOtherServersStatusesOnlyOnceTheyreOldEnough() {
        var status = remoteStatus(remoteAuthor());

        expiry.run(Instant.now());
        assertThat(exists(status)).isTrue();

        expiry.run(Instant.now().plus(LATER));
        assertThat(exists(status)).isFalse();
    }

    @Test
    void neverRemovesLocalStatuses() {
        var local = statuses.create(account(), new NewStatus("mine", "", null, Visibility.PUBLIC, false, null)).id();

        expiry.run(Instant.now().plus(LATER));

        assertThat(exists(local)).isTrue();
    }

    @Test
    void keepsWhatSomeoneHereInteractedWith() {
        var author = remoteAuthor();
        var local = account();
        var favourited = remoteStatus(author);
        likes.favourite(favourited, local).orElseThrow();
        var bookmarked = remoteStatus(author);
        bookmarks.bookmark(bookmarked, local).orElseThrow();
        var boosted = remoteStatus(author);
        boosts.reblog(boosted, local, Visibility.PUBLIC).orElseThrow();
        var repliedTo = remoteStatus(author);
        statuses.create(local, new NewStatus("a reply", "", null, Visibility.PUBLIC, false, repliedTo));
        var mentioning = store(author, Map.of(local, false), null);
        var unwanted = remoteStatus(author);

        expiry.run(Instant.now().plus(LATER));

        assertThat(exists(unwanted)).isFalse();
        assertThat(List.of(favourited, bookmarked, boosted, repliedTo, mentioning)).allMatch(this::exists);
    }

    @Test
    void keepsAThreadThatsStillActive() {
        var author = remoteAuthor();
        var parent = remoteStatus(author);
        // Stored a month later, by when the parent is old.
        var reply = store(author, Map.of(), uriOf(parent));
        jdbc.sql("UPDATE status SET created_at = now() + interval '31 days' WHERE id = :id").param("id", reply).update();

        expiry.run(Instant.now().plus(LATER));

        assertThat(exists(parent)).isTrue();
        assertThat(exists(reply)).isTrue();
    }

    @Test
    void keepsWhatsInNotificationsAndConversations() {
        var author = remoteAuthor();
        var local = account();
        // Inserted rather than notified, since what notifies someone of a status, such as mentioning them, is kept for
        // that reason as well, which would hide whether these are.
        var notified = remoteStatus(author);
        jdbc.sql("""
                        INSERT INTO notification (id, recipient_id, sender_id, type, source_id, status_id, created_at)
                        VALUES (gen_random_uuid(), :recipient, :sender, 'status', :status, :status, now())
                        """)
                .param("recipient", local).param("sender", author.id()).param("status", notified).update();
        var inConversation = remoteStatus(author);
        var conversation = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO conversation (id, owner_id, thread_id, participant_ids)
                        VALUES (:id, :owner, (SELECT thread_id FROM status WHERE id = :status), ARRAY[CAST(:author AS uuid)])
                        """)
                .param("id", conversation).param("owner", local).param("status", inConversation)
                .param("author", author.id().toString()).update();
        jdbc.sql("INSERT INTO conversation_status (conversation_id, status_id) VALUES (:conversation, :status)")
                .param("conversation", conversation).param("status", inConversation).update();
        var unwanted = remoteStatus(author);

        expiry.run(Instant.now().plus(LATER));

        assertThat(exists(unwanted)).isFalse();
        assertThat(List.of(notified, inConversation)).allMatch(this::exists);
    }

    @Test
    void keepsReportedStatuses() {
        var author = remoteAuthor();
        var reported = remoteStatus(author);
        reports.file(account(), author, List.of(statuses.findForModeration(reported.toString()).orElseThrow()),
                "spam", ReportCategory.SPAM, false);
        var unwanted = remoteStatus(author);

        expiry.run(Instant.now().plus(LATER));

        assertThat(exists(unwanted)).isFalse();
        assertThat(exists(reported)).isTrue();
    }

    @Test
    void keepsAStatusThatsQuotedUntilItsQuoteIsRemoved() {
        var author = remoteAuthor();
        var quoted = remoteStatus(author);
        var quote = store(author, Map.of(), null, new RemoteQuote(uriOf(quoted), true, null));
        assertThat(quotedBy(quote)).isEqualTo(quoted);
        var later = Instant.now().plus(LATER);

        expiry.run(later);

        assertThat(exists(quote)).isFalse();
        assertThat(exists(quoted)).isTrue();

        // It's found on the next pass back over what was kept.
        var nextPass = later.plus(RemoteStatusExpiry.PASS_INTERVAL).plus(Duration.ofDays(1));
        expiry.run(nextPass);
        expiry.run(nextPass);

        assertThat(exists(quoted)).isFalse();
    }

    @Test
    void keepsAQuoteOfALocalStatus() {
        var local = statuses.create(account(), new NewStatus("mine", "", null, Visibility.PUBLIC, false, null));
        var quote = store(remoteAuthor(), Map.of(), null, new RemoteQuote(local.uri(), true, null));
        assertThat(jdbc.sql("SELECT quoted_account_id FROM status_quote WHERE status_id = :id").param("id", quote)
                .query(UUID.class).single()).isEqualTo(local.actorId());

        expiry.run(Instant.now().plus(LATER));

        assertThat(exists(quote)).isTrue();
    }

    @Test
    void keepsAPollSomeoneHereVotedIn() {
        var author = remoteAuthor();
        var voted = storePoll(author);
        polls.vote(voted, account(), List.of(0)).orElseThrow();
        var notVoted = storePoll(author);

        expiry.run(Instant.now().plus(LATER));

        assertThat(exists(notVoted)).isFalse();
        assertThat(exists(voted)).isTrue();
    }

    @Test
    void keepsWhatItsAuthorPinned() {
        var author = remoteAuthor();
        var pinned = remoteStatus(author);
        pins.replaceRemote(author.id(), Map.of(pinned, Instant.now()), Instant.now());
        var unwanted = remoteStatus(author);

        expiry.run(Instant.now().plus(LATER));

        assertThat(exists(unwanted)).isFalse();
        assertThat(exists(pinned)).isTrue();
    }

    @Test
    void aRemovedStatusCanBeStoredAgain() {
        var author = remoteAuthor();
        var status = remoteStatus(author);
        var uri = uriOf(status);

        expiry.run(Instant.now().plus(LATER));

        assertThat(exists(status)).isFalse();
        assertThat(remoteStatuses.storeRemote(remote(uri, author, Map.of(), null, null))).isPresent();
    }

    @Test
    void goesOnFromWhereTheLastRunStopped() {
        var author = remoteAuthor();
        // More than a run removes, even if it stops a window past its limit.
        var count = RemoteStatusExpiry.MAX_REMOVED_PER_RUN + RemoteStatusExpiry.WINDOW + 1;
        bulkInsert(author, count);
        var later = Instant.now().plus(LATER);

        // Other tests' statuses can be removed with them, so only the limit is exact.
        assertThat(expiry.run(later)).isBetween(RemoteStatusExpiry.MAX_REMOVED_PER_RUN,
                RemoteStatusExpiry.MAX_REMOVED_PER_RUN + RemoteStatusExpiry.WINDOW - 1);
        assertThat(countBy(author)).isPositive();
        expiry.run(later);

        assertThat(countBy(author)).isZero();
    }

    @Test
    void aRunOnlyChecksSoManyStatuses() {
        // Statuses that are never removed, ahead of one that is.
        statuses.create(account(), new NewStatus("first", "", null, Visibility.PUBLIC, false, null));
        statuses.create(account(), new NewStatus("second", "", null, Visibility.PUBLIC, false, null));
        statuses.create(account(), new NewStatus("third", "", null, Visibility.PUBLIC, false, null));
        var status = remoteStatus(remoteAuthor());
        var later = Instant.now().plus(LATER);

        expiry.run(later, 2, 1);
        assertThat(exists(status)).isTrue();

        // It's reached by going on from each window, however many statuses other tests left ahead of it.
        for (var runs = 0; runs < 10_000 && exists(status); runs++) {
            expiry.run(later, 2, 1);
        }
        assertThat(exists(status)).isFalse();
    }

    private void bulkInsert(Actor author, int count) {
        jdbc.sql("""
                        INSERT INTO status (id, uri, actor_id, text, content, visibility, published_at)
                        SELECT uuidv7(), :prefix || n, :author, '', '', 'public', now() FROM generate_series(1, :count) n
                        """)
                .param("prefix", author.uri() + "/bulk/").param("author", author.id()).param("count", count).update();
    }

    private long countBy(Actor author) {
        return jdbc.sql("SELECT count(*) FROM status WHERE actor_id = :author")
                .param("author", author.id()).query(Long.class).single();
    }

    private Actor remoteAuthor() {
        var host = "expiry-" + UUID.randomUUID() + ".example";
        var uri = URI.create("https://" + host + "/users/r");
        return actors.upsertRemote(
                new RemoteActorProfile(uri, "r", host, URI.create(uri + "/inbox"), null, null, null, null));
    }

    private UUID remoteStatus(Actor author) {
        return store(author, Map.of(), null);
    }

    private UUID store(Actor author, Map<UUID, Boolean> mentions, URI inReplyTo) {
        return store(author, mentions, inReplyTo, null);
    }

    private UUID store(Actor author, Map<UUID, Boolean> mentions, URI inReplyTo, RemoteQuote quote) {
        var uri = URI.create(author.uri() + "/notes/" + UUID.randomUUID());
        return remoteStatuses.storeRemote(remote(uri, author, mentions, inReplyTo, quote)).orElseThrow();
    }

    private static RemoteStatus remote(URI uri, Actor author, Map<UUID, Boolean> mentions, URI inReplyTo,
                                       RemoteQuote quote) {
        return new RemoteStatus(uri, null, author.id(), "<p>From afar</p>", null, null, Visibility.PUBLIC, false,
                inReplyTo, Instant.now(), null, mentions, List.of(), List.of(), quote, null, null);
    }

    private UUID storePoll(Actor author) {
        var uri = URI.create(author.uri() + "/notes/" + UUID.randomUUID());
        var poll = new RemotePoll(List.of("yes", "no"), List.of(0, 0), false,
                Instant.now().plus(Duration.ofDays(60)), null, 0);
        return remoteStatuses.storeRemote(new RemoteStatus(uri, null, author.id(), "<p>Which?</p>", null, null,
                Visibility.PUBLIC, false, null, Instant.now(), null, Map.of(), List.of(), List.of(), null, null,
                poll)).orElseThrow();
    }

    private UUID quotedBy(UUID quote) {
        return jdbc.sql("SELECT quoted_status_id FROM status_quote WHERE status_id = :id").param("id", quote)
                .query(UUID.class).single();
    }

    private UUID account() {
        return actors.createLocalIfAbsent("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 25),
                actor -> {
                }).orElseThrow().id();
    }

    private URI uriOf(UUID statusId) {
        return URI.create(jdbc.sql("SELECT uri FROM status WHERE id = :id").param("id", statusId)
                .query(String.class).single());
    }

    private boolean exists(UUID statusId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM status WHERE id = :id)")
                .param("id", statusId).query(Boolean.class).single();
    }
}
