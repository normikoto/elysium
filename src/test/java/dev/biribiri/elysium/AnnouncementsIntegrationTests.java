package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.ActorDataRemoval;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.announcement.Announcement;
import dev.biribiri.elysium.announcement.Announcements;
import dev.biribiri.elysium.content.CustomEmojis;
import dev.biribiri.elysium.content.RemoteEmoji;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Announcements: the admin page that writes them, and the Mastodon API that shows, dismisses, and reacts to them.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://elysium.example",
        "elysium.jobs.worker-enabled=false"
})
@AutoConfigureMockMvc
class AnnouncementsIntegrationTests {

    private static final String PAGE = "/admin/announcements";
    private static final String API = "/api/v1/announcements";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String ADMIN = "announceadmin";
    private static final String USER = "announceuser";
    private static final String OTHER = "announceother";
    private static final Map<String, String> TOKENS = new HashMap<>();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;
    @Autowired
    private Announcements announcements;
    @Autowired
    private CustomEmojis emojis;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private List<ActorDataRemoval> removals;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("DELETE FROM announcement").update();
        TestAccounts.makeSureAnAccountExists(actors);
        ensureAccount(ADMIN);
        ensureAccount(USER);
        ensureAccount(OTHER);
        TestAccounts.makeAdmin(jdbc, actors.findLocalByUsername(ADMIN).orElseThrow().id());
    }

    // The admin page

    @Test
    void addsEditsPublishesAndRemovesAnAnnouncementOnThePage() throws Exception {
        var session = logIn(ADMIN);

        mockMvc.perform(post(PAGE).session(session).with(csrf())
                        .param("text", "  <b>Maintenance</b> tonight  ")
                        .param("startsAt", "2026-12-01T20:00")
                        .param("endsAt", "2026-12-01T22:00"))
                .andExpect(redirectedUrl(PAGE));
        var draft = announcements.list().getFirst();
        assertThat(draft.text()).isEqualTo("<b>Maintenance</b> tonight");
        assertThat(draft.startsAt()).isEqualTo(Instant.parse("2026-12-01T20:00:00Z"));
        assertThat(draft.endsAt()).isEqualTo(Instant.parse("2026-12-01T22:00:00Z"));
        assertThat(draft.isPublished()).isFalse();
        mockMvc.perform(get(PAGE).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;b&gt;Maintenance&lt;/b&gt; tonight")))
                .andExpect(content().string(containsString("2026-12-01T20:00")))
                .andExpect(content().string(containsString("Draft")));

        mockMvc.perform(post(PAGE + "/" + draft.id()).session(session).with(csrf())
                        .param("text", "Maintenance all day")
                        .param("startsAt", "2026-12-01T00:00")
                        .param("endsAt", "2026-12-02T00:00")
                        .param("allDay", "true"))
                .andExpect(redirectedUrl(PAGE));
        assertThat(announcements.list()).singleElement().satisfies(edited -> {
            assertThat(edited.text()).isEqualTo("Maintenance all day");
            assertThat(edited.allDay()).isTrue();
        });

        mockMvc.perform(post(PAGE + "/" + draft.id() + "/publish").session(session).with(csrf()))
                .andExpect(redirectedUrl(PAGE));
        assertThat(announcements.listPublished()).hasSize(1);
        mockMvc.perform(post(PAGE + "/" + draft.id() + "/unpublish").session(session).with(csrf()))
                .andExpect(redirectedUrl(PAGE));
        assertThat(announcements.listPublished()).isEmpty();

        mockMvc.perform(post(PAGE + "/" + draft.id() + "/delete").session(session).with(csrf()))
                .andExpect(redirectedUrl(PAGE));
        assertThat(announcements.list()).isEmpty();
    }

    @Test
    void publishesRightAwayWhenAsked() throws Exception {
        mockMvc.perform(post(PAGE).session(logIn(ADMIN)).with(csrf())
                        .param("text", "Welcome!")
                        .param("publish", "true"))
                .andExpect(redirectedUrl(PAGE));
        assertThat(announcements.listPublished()).singleElement()
                .satisfies(announcement -> assertThat(announcement.publishedAt()).isNotNull());
    }

    @Test
    void showsWhyAnAnnouncementWasRefusedOnThePage() throws Exception {
        var session = logIn(ADMIN);

        mockMvc.perform(post(PAGE).session(session).with(csrf()).param("text", " "))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("An announcement needs some text.")));
        mockMvc.perform(post(PAGE).session(session).with(csrf())
                        .param("text", "Kept text")
                        .param("startsAt", "2026-12-01T20:00"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("both a start and an end")))
                .andExpect(content().string(containsString("Kept text")));
        mockMvc.perform(post(PAGE).session(session).with(csrf())
                        .param("text", "Backwards")
                        .param("startsAt", "2026-12-02T20:00")
                        .param("endsAt", "2026-12-01T20:00"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("end before it starts")));
        mockMvc.perform(post(PAGE).session(session).with(csrf())
                        .param("text", "Bad time")
                        .param("startsAt", "tomorrow")
                        .param("endsAt", "later"))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(post(PAGE).session(session).with(csrf())
                        .param("text", "x".repeat(Announcements.MAX_TEXT_LENGTH + 1)))
                .andExpect(status().isUnprocessableContent());
        assertThat(announcements.list()).isEmpty();
    }

    @Test
    void regularUsersCannotUseThePage() throws Exception {
        var session = logIn(USER);

        mockMvc.perform(get(PAGE).session(session)).andExpect(status().isForbidden());
        mockMvc.perform(post(PAGE).session(session).with(csrf()).param("text", "Hi"))
                .andExpect(status().isForbidden());
        assertThat(announcements.list()).isEmpty();
    }

    // The API

    @Test
    void listsPublishedAnnouncementsInOrder() throws Exception {
        var later = announcements.create("Party #Fediverse :blobfox:", Instant.parse("2027-01-10T00:00:00Z"),
                Instant.parse("2027-01-11T00:00:00Z"), true, true);
        var sooner = announcements.create("Visit https://example.com/news", null, null, false, true);
        announcements.create("Not yet", null, null, false, false);

        mockMvc.perform(get(API).header("Authorization", "Bearer " + token(USER, "read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(sooner.id().toString()))
                .andExpect(jsonPath("$[0].content").value(containsString("<a href=\"https://example.com/news\"")))
                .andExpect(jsonPath("$[0].starts_at").doesNotExist())
                .andExpect(jsonPath("$[0].read").value(false))
                .andExpect(jsonPath("$[0].published").value(true))
                .andExpect(jsonPath("$[0].mentions", hasSize(0)))
                .andExpect(jsonPath("$[0].statuses", hasSize(0)))
                .andExpect(jsonPath("$[0].reactions", hasSize(0)))
                .andExpect(jsonPath("$[1].id").value(later.id().toString()))
                .andExpect(jsonPath("$[1].starts_at").value("2027-01-10T00:00:00.000Z"))
                .andExpect(jsonPath("$[1].ends_at").value("2027-01-11T00:00:00.000Z"))
                .andExpect(jsonPath("$[1].all_day").value(true))
                .andExpect(jsonPath("$[1].tags[0].name").value("fediverse"))
                .andExpect(jsonPath("$[1].tags[0].url").value("https://elysium.example/tags/fediverse"));
    }

    @Test
    void namesTheCustomEmojiEachAnnouncementUses() throws Exception {
        var shortcode = shortcode();
        var otherShortcode = shortcode();
        emojis.addLocal(shortcode, TestImages.png(64, 64), null);
        emojis.addLocal(otherShortcode, TestImages.png(64, 64), null);
        announcements.create("Hello :" + shortcode + ":", null, null, false, true);
        announcements.create("Goodbye :" + otherShortcode + ":", null, null, false, true);

        mockMvc.perform(get(API).header("Authorization", "Bearer " + token(USER, "read")))
                .andExpect(jsonPath("$[0].emojis", hasSize(1)))
                .andExpect(jsonPath("$[0].emojis[0].shortcode").value(shortcode))
                .andExpect(jsonPath("$[1].emojis", hasSize(1)))
                .andExpect(jsonPath("$[1].emojis[0].shortcode").value(otherShortcode));
    }

    @Test
    void readsLineBreaksFromTheBrowserAsParagraphs() throws Exception {
        var announcement = announcements.create("Maintenance tonight\r\n\r\nDetails below\r\n", null, null, false,
                true);
        assertThat(announcement.text()).isEqualTo("Maintenance tonight\n\nDetails below");
        announcements.create("a\r\n".repeat(Announcements.MAX_TEXT_LENGTH / 2 - 1) + "a", null, null, false, false);

        mockMvc.perform(get(API).header("Authorization", "Bearer " + token(USER, "read")))
                .andExpect(jsonPath("$[0].content").value("<p>Maintenance tonight</p><p>Details below</p>"));
    }

    @Test
    void dismissingMarksItRead() throws Exception {
        var announcement = published();

        mockMvc.perform(post(API + "/" + announcement.id() + "/dismiss")
                        .header("Authorization", "Bearer " + token(USER, "write:accounts")))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));
        mockMvc.perform(post(API + "/" + announcement.id() + "/dismiss")
                        .header("Authorization", "Bearer " + token(USER, "write:accounts")))
                .andExpect(status().isOk());

        mockMvc.perform(get(API).header("Authorization", "Bearer " + token(USER, "read")))
                .andExpect(jsonPath("$[0].read").value(true));
        mockMvc.perform(get(API).header("Authorization", "Bearer " + token(OTHER, "read")))
                .andExpect(jsonPath("$[0].read").value(false));
    }

    @Test
    void countsReactionsFromEveryone() throws Exception {
        var announcement = published();
        var shortcode = shortcode();
        emojis.addLocal(shortcode, TestImages.png(64, 64), null);

        react(USER, announcement.id(), "👍").andExpect(status().isOk()).andExpect(content().json("{}"));
        react(USER, announcement.id(), "👍").andExpect(status().isOk());
        react(OTHER, announcement.id(), "👍").andExpect(status().isOk());
        react(OTHER, announcement.id(), ":" + shortcode + ":").andExpect(status().isOk());

        mockMvc.perform(get(API).header("Authorization", "Bearer " + token(USER, "read")))
                .andExpect(jsonPath("$[0].reactions", hasSize(2)))
                .andExpect(jsonPath("$[0].reactions[0].name").value("👍"))
                .andExpect(jsonPath("$[0].reactions[0].count").value(2))
                .andExpect(jsonPath("$[0].reactions[0].me").value(true))
                .andExpect(jsonPath("$[0].reactions[0].url").doesNotExist())
                .andExpect(jsonPath("$[0].reactions[1].name").value(shortcode))
                .andExpect(jsonPath("$[0].reactions[1].count").value(1))
                .andExpect(jsonPath("$[0].reactions[1].me").value(false))
                .andExpect(jsonPath("$[0].reactions[1].url").value(containsString("https://elysium.example/")))
                .andExpect(jsonPath("$[0].reactions[1].static_url").exists());

        unreact(USER, announcement.id(), "👍").andExpect(status().isOk());
        unreact(USER, announcement.id(), "👍").andExpect(status().isOk());
        mockMvc.perform(get(API).header("Authorization", "Bearer " + token(USER, "read")))
                .andExpect(jsonPath("$[0].reactions[0].count").value(1))
                .andExpect(jsonPath("$[0].reactions[0].me").value(false));
    }

    @Test
    void refusesEmojiThatCannotBeUsed() throws Exception {
        var announcement = published();
        var remote = shortcode();
        transactions.executeWithoutResult(status -> emojis.storeRemote("elsewhere.example", List.of(new RemoteEmoji(
                remote, null, URI.create("https://elsewhere.example/emoji.png"), null))));

        react(USER, announcement.id(), "not an emoji").andExpect(status().isUnprocessableContent());
        react(USER, announcement.id(), remote + "@elsewhere.example")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("this server's custom emoji")));
    }

    @Test
    void limitsHowManyEmojiAnAnnouncementHas() throws Exception {
        var announcement = published();
        var emoji = List.of("😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊");

        for (var i = 0; i < Announcements.MAX_REACTIONS; i++) {
            react(i % 2 == 0 ? USER : OTHER, announcement.id(), emoji.get(i)).andExpect(status().isOk());
        }
        react(USER, announcement.id(), emoji.get(Announcements.MAX_REACTIONS))
                .andExpect(status().isUnprocessableContent());
        // One that's been used already can still be added.
        react(OTHER, announcement.id(), emoji.getFirst()).andExpect(status().isOk());
    }

    @Test
    void unknownAndUnpublishedAnnouncementsAreNotFound() throws Exception {
        var draft = announcements.create("Draft", null, null, false, false);
        var dismissToken = token(USER, "write:accounts");

        for (var id : List.of(draft.id().toString(), UUID.randomUUID().toString(), "not-an-id")) {
            mockMvc.perform(post(API + "/" + id + "/dismiss").header("Authorization", "Bearer " + dismissToken))
                    .andExpect(status().isNotFound());
            react(USER, id, "👍").andExpect(status().isNotFound());
            unreact(USER, id, "👍").andExpect(status().isNotFound());
        }
    }

    @Test
    void needsTheRightScopesAndAUser() throws Exception {
        var announcement = published();
        var readOnly = token(USER, "read");

        mockMvc.perform(get(API)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(API + "/" + announcement.id() + "/dismiss").header("Authorization", "Bearer " + readOnly))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(API + "/" + announcement.id() + "/reactions/👍")
                        .header("Authorization", "Bearer " + readOnly))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(API).header("Authorization", "Bearer " + appToken()))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void deletingAnAccountRemovesItsDismissalsAndReactions() throws Exception {
        var announcement = published();
        var username = "announcegone" + UUID.randomUUID().toString().substring(0, 8);
        ensureAccount(username);
        var actorId = actors.findLocalByUsername(username).orElseThrow().id();
        announcements.dismiss(announcement.id(), actorId);
        announcements.react(announcement.id(), actorId, "👍");

        removals.forEach(removal -> removal.removeForDeleted(actorId));

        assertThat(announcements.dismissedBy(actorId, List.of(announcement.id()))).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM announcement_reaction WHERE actor_id = :id")
                .param("id", actorId).query(Integer.class).single()).isZero();
    }

    // Helpers

    private Announcement published() {
        return announcements.create("Hello, everyone", null, null, false, true);
    }

    private ResultActions react(String username, Object id, String emoji)
            throws Exception {
        return mockMvc.perform(put(API + "/" + id + "/reactions/{name}", emoji)
                .header("Authorization", "Bearer " + token(username, "write:favourites")));
    }

    private ResultActions unreact(String username, Object id, String emoji)
            throws Exception {
        return mockMvc.perform(delete(API + "/" + id + "/reactions/{name}", emoji)
                .header("Authorization", "Bearer " + token(username, "write:favourites")));
    }

    private static String shortcode() {
        return "ann" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private void ensureAccount(String username) throws Exception {
        if (actors.findLocalByUsername(username).isEmpty()) {
            mockMvc.perform(post("/signup").with(csrf())
                            .param("invite", TestAccounts.invite(jdbc))
                            .param("username", username)
                            .param("password", PASSWORD)
                            .param("confirmPassword", PASSWORD))
                    .andExpect(status().is3xxRedirection());
        }
    }

    private MockHttpSession logIn(String username) throws Exception {
        return TestOAuth.logIn(mockMvc, username, PASSWORD);
    }

    private String token(String username, String scope) throws Exception {
        var key = username + " " + scope;
        var cached = TOKENS.get(key);
        if (cached != null) {
            return cached;
        }
        var token = TestOAuth.userToken(mockMvc, username, PASSWORD, scope);
        TOKENS.put(key, token);
        return token;
    }

    private String appToken() throws Exception {
        var cached = TOKENS.get("app");
        if (cached != null) {
            return cached;
        }
        var app = TestOAuth.registerApp(mockMvc, "read write");
        var body = mockMvc.perform(post("/oauth/token")
                        .param("grant_type", "client_credentials")
                        .param("scope", "read write")
                        .param("client_id", app.get("client_id"))
                        .param("client_secret", app.get("client_secret")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.access_token");
        TOKENS.put("app", token);
        return token;
    }
}
