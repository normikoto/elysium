package dev.biribiri.elysium.content;

import dev.biribiri.elysium.TestAccounts;
import dev.biribiri.elysium.TestOAuth;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.Ids;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The job is off here, so that tests run the cleanup themselves, as if it were later, rather than wait for statuses to age.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.jobs.worker-enabled=false")
@AutoConfigureMockMvc
class PostCleanupIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String PAGE = "/settings/post-cleanup";

    /**
     * Past the default policy's two weeks.
     */
    private static final Duration LATER = Duration.ofDays(15);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;
    @Autowired
    private StatusService statuses;
    @Autowired
    private BoostService boosts;
    @Autowired
    private StatusPinService pins;
    @Autowired
    private LikeService likes;
    @Autowired
    private BookmarkService bookmarks;
    @Autowired
    private PostCleanups cleanups;

    @BeforeEach
    void setUp() {
        TestAccounts.makeSureAnAccountExists(actors);
        // Other tests' policies would take turns from these.
        jdbc.sql("DELETE FROM post_cleanup_policy").update();
    }

    @Test
    void deletesOldStatusesOnlyOnceTheyreOldEnough() {
        var author = account();
        var status = newStatus(author, Visibility.PUBLIC);
        enable(author, PostCleanupPolicy.DEFAULT);

        assertThat(cleanups.run(Instant.now())).isZero();
        assertThat(exists(status)).isTrue();

        assertThat(cleanups.run(Instant.now().plus(LATER))).isEqualTo(1);
        assertThat(exists(status)).isFalse();
        // Deleted as its author would, so other servers are told through its tombstone and Delete.
        assertThat(statuses.findTombstone(status, author)).isPresent();
    }

    @Test
    void aPolicyThatIsOffDeletesNothing() {
        var author = account();
        var status = newStatus(author, Visibility.PUBLIC);
        cleanups.save(author, PostCleanupPolicy.DEFAULT);

        cleanups.run(Instant.now().plus(LATER));

        assertThat(exists(status)).isTrue();
    }

    @Test
    void keepsWhatThePolicySaysToKeep() {
        var author = account();
        var fan = account();
        var deleted = newStatus(author, Visibility.PUBLIC);
        var pinned = newStatus(author, Visibility.PUBLIC);
        pins.pin(pinned, author).orElseThrow();
        var direct = newStatus(author, Visibility.DIRECT);
        var poll = statuses.create(author, new NewStatus("poll", "", null, Visibility.PUBLIC, false, null, List.of(),
                null, null, new NewPoll(List.of("yes", "no"), 3600, false, false), false)).id();
        var media = newStatus(author, Visibility.PUBLIC);
        attachMedia(author, media);
        var favourited = newStatus(author, Visibility.PUBLIC);
        likes.favourite(favourited, author).orElseThrow();
        var bookmarked = newStatus(author, Visibility.PUBLIC);
        bookmarks.bookmark(bookmarked, author).orElseThrow();
        var popular = newStatus(author, Visibility.PUBLIC);
        likes.favourite(popular, fan).orElseThrow();
        var boosted = newStatus(author, Visibility.PUBLIC);
        boosts.reblog(boosted, fan, Visibility.PUBLIC).orElseThrow();
        enable(author, new PostCleanupPolicy(true, PostCleanupPolicy.MinimumAge.TWO_WEEKS,
                true, true, true, true, true, true, 1, 1));

        cleanups.run(Instant.now().plus(LATER));

        assertThat(exists(deleted)).isFalse();
        assertThat(List.of(pinned, direct, poll, media, favourited, bookmarked, popular, boosted))
                .allMatch(this::exists);
    }

    @Test
    void deletesWhatThePolicyDoesNotKeep() {
        var author = account();
        var pinned = newStatus(author, Visibility.PUBLIC);
        pins.pin(pinned, author).orElseThrow();
        var direct = newStatus(author, Visibility.DIRECT);
        var favourited = newStatus(author, Visibility.PUBLIC);
        likes.favourite(favourited, author).orElseThrow();
        enable(author, new PostCleanupPolicy(true, PostCleanupPolicy.MinimumAge.TWO_WEEKS,
                false, false, false, false, false, false, null, null));

        cleanups.run(Instant.now().plus(LATER));

        assertThat(List.of(pinned, direct, favourited)).noneMatch(this::exists);
    }

    @Test
    void undoesOldBoosts() {
        var author = account();
        var booster = account();
        var status = newStatus(author, Visibility.PUBLIC);
        var boost = boosts.reblog(status, booster, Visibility.PUBLIC).orElseThrow().id();
        enable(booster, PostCleanupPolicy.DEFAULT);

        cleanups.run(Instant.now().plus(LATER));

        assertThat(exists(boost)).isFalse();
        assertThat(exists(status)).isTrue();
    }

    @Test
    void undoesOldBoostsOfSuspendedAuthors() {
        var author = account();
        var booster = account();
        var status = newStatus(author, Visibility.PUBLIC);
        var boost = boosts.reblog(status, booster, Visibility.PUBLIC).orElseThrow().id();
        actors.suspend(author);
        enable(booster, PostCleanupPolicy.DEFAULT);

        cleanups.run(Instant.now().plus(LATER));

        assertThat(exists(boost)).isFalse();
    }

    @Test
    void deletesOnlySomeOfAnAccountsStatusesEachRun() {
        var author = account();
        for (var i = 0; i < PostCleanups.PER_ACCOUNT + 1; i++) {
            newStatus(author, Visibility.PUBLIC);
        }
        enable(author, PostCleanupPolicy.DEFAULT);

        assertThat(cleanups.run(Instant.now().plus(LATER))).isEqualTo(PostCleanups.PER_ACCOUNT);
        assertThat(cleanups.run(Instant.now().plus(LATER))).isEqualTo(1);
    }

    @Test
    void goesOnFromWhereTheLastRunStoppedAndStartsAgainAtTheEnd() {
        var author = account();
        var first = pinnedStatus(author);
        var second = pinnedStatus(author);
        var third = newStatus(author, Visibility.PUBLIC);
        enable(author, PostCleanupPolicy.DEFAULT);
        var later = Instant.now().plus(LATER);

        // Only looks at the first two, which are kept.
        assertThat(cleanups.run(later, 2)).isZero();
        assertThat(cleanups.run(later, 2)).isEqualTo(1);
        assertThat(exists(third)).isFalse();

        pins.unpin(first, author).orElseThrow();
        assertThat(cleanups.run(later, 2)).isEqualTo(1);
        assertThat(exists(first)).isFalse();
        assertThat(exists(second)).isTrue();
    }

    @Test
    void savingThePolicyStartsTheScanAgain() {
        var author = account();
        var first = pinnedStatus(author);
        var second = pinnedStatus(author);
        newStatus(author, Visibility.PUBLIC);
        enable(author, PostCleanupPolicy.DEFAULT);
        var later = Instant.now().plus(LATER);
        assertThat(cleanups.run(later, 2)).isZero();

        enable(author, new PostCleanupPolicy(true, PostCleanupPolicy.MinimumAge.TWO_WEEKS,
                false, true, false, false, true, true, null, null));

        assertThat(cleanups.run(later, 2)).isEqualTo(2);
        assertThat(List.of(first, second)).noneMatch(this::exists);
    }

    @Test
    void waitsWhileTheAccountIsSuspended() {
        var author = account();
        var status = newStatus(author, Visibility.PUBLIC);
        enable(author, PostCleanupPolicy.DEFAULT);
        actors.suspend(author);

        cleanups.run(Instant.now().plus(LATER));

        assertThat(exists(status)).isTrue();
    }

    @Test
    void thePageNeedsALogin() throws Exception {
        mockMvc.perform(get(PAGE).accept(MediaType.TEXT_HTML)).andExpect(redirectedUrl("/login"));
    }

    @Test
    void thePageSavesThePolicy() throws Exception {
        var username = signUp();
        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);
        var actorId = actors.findLocalByUsername(username).orElseThrow().id();
        mockMvc.perform(get("/settings").session(session))
                .andExpect(content().string(containsString("href=\"" + PAGE + "\"")));

        mockMvc.perform(post(PAGE).session(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(PAGE).session(session).with(csrf())
                        .param("enabled", "true")
                        .param("minAgeDays", "30")
                        .param("keepPinned", "true")
                        .param("minFavourites", "5")
                        .param("minBoosts", ""))
                .andExpect(redirectedUrl(PAGE + "?saved"));

        assertThat(cleanups.find(actorId)).isEqualTo(new PostCleanupPolicy(true, PostCleanupPolicy.MinimumAge.ONE_MONTH,
                true, false, false, false, false, false, 5, null));
        mockMvc.perform(get(PAGE).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"5\"")));
    }

    @Test
    void thePageRefusesAnAgeMastodonDoesNotOffer() throws Exception {
        var username = signUp();
        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);
        var actorId = actors.findLocalByUsername(username).orElseThrow().id();

        mockMvc.perform(post(PAGE).session(session).with(csrf())
                        .param("enabled", "true")
                        .param("minAgeDays", "1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("Nothing was saved")));

        assertThat(cleanups.find(actorId)).isEqualTo(PostCleanupPolicy.DEFAULT);
    }

    @Test
    void aRefusedSaveShowsWhatWasSent() throws Exception {
        var username = signUp();
        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);

        var page = mockMvc.perform(post(PAGE).session(session).with(csrf())
                        .param("enabled", "true")
                        .param("minAgeDays", "90")
                        .param("keepPolls", "true")
                        .param("minFavourites", "7")
                        .param("minBoosts", "0"))
                .andExpect(status().isUnprocessableContent())
                .andReturn().getResponse().getContentAsString();

        assertThat(page)
                .containsPattern("name=\"enabled\" value=\"true\" checked")
                .containsPattern("value=\"90\" selected")
                .containsPattern("name=\"keepPolls\" value=\"true\" checked")
                // Kept by default, but unchecked in what was sent.
                .containsPattern("name=\"keepPinned\" value=\"true\">")
                .contains("value=\"7\"", "value=\"0\"");
    }

    private void enable(UUID actorId, PostCleanupPolicy policy) {
        cleanups.save(actorId, new PostCleanupPolicy(true, policy.minimumAge(), policy.keepPinned(), policy.keepDirect(),
                policy.keepPolls(), policy.keepMedia(), policy.keepSelfFavourited(), policy.keepSelfBookmarked(),
                policy.minFavourites(), policy.minBoosts()));
    }

    private UUID account() {
        return actors.createLocalIfAbsent(newUsername(), actor -> {
        }).orElseThrow().id();
    }

    private String signUp() throws Exception {
        var username = newUsername();
        mockMvc.perform(post("/signup").with(csrf())
                        .param("invite", TestAccounts.invite(jdbc))
                        .param("username", username)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(status().is3xxRedirection());
        return username;
    }

    private UUID newStatus(UUID author, Visibility visibility) {
        return statuses.create(author, new NewStatus("an old post", "", null, visibility, false, null)).id();
    }

    private UUID pinnedStatus(UUID author) {
        var status = newStatus(author, Visibility.PUBLIC);
        pins.pin(status, author).orElseThrow();
        return status;
    }

    private void attachMedia(UUID author, UUID statusId) {
        jdbc.sql("""
                        INSERT INTO media_attachment (id, account_id, status_id, type, file_name, content_type)
                        VALUES (:id, :author, :status, 'image', 'cleanup-test.png', 'image/png')
                        """)
                .param("id", Ids.timeOrdered(Instant.now()))
                .param("author", author)
                .param("status", statusId)
                .update();
    }

    private boolean exists(UUID statusId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM status WHERE id = :id)")
                .param("id", statusId).query(Boolean.class).single();
    }

    private static String newUsername() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 25);
    }
}
