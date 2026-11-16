package dev.biribiri.elysium;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.ProfileChange;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.content.CustomEmoji;
import dev.biribiri.elysium.content.CustomEmojis;
import dev.biribiri.elysium.content.InvalidCustomEmojiException;
import dev.biribiri.elysium.content.NewPoll;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.StatusEdit;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.media.MediaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * This server's own custom emoji: the admin page, the API, and how they're shown and sent. Other servers' are tested in
 * {@code CreateIntegrationTests} and {@code MediaDownloadIntegrationTests}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://emoji.example",
        "elysium.jobs.worker-enabled=false",
        // Lower than the default, so that the tests show the setting is what's used.
        "elysium.custom-emojis.max-image-size=200KB"
})
@AutoConfigureMockMvc
class CustomEmojisIntegrationTests {

    private static final String PAGE = "/admin/custom-emojis";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String ADMIN = "emojiadmin";
    private static final String USER = "emojiuser";
    private static final String ACTIVITY_JSON = "application/activity+json";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;
    @Autowired
    private CustomEmojis emojis;
    @Autowired
    private StatusService statuses;
    @Autowired
    private ProfileService profiles;
    @Autowired
    private MediaProperties media;

    private Actor user;

    @BeforeEach
    void setUp() throws Exception {
        TestAccounts.makeSureAnAccountExists(actors);
        ensureAccount(ADMIN);
        ensureAccount(USER);
        TestAccounts.makeAdmin(jdbc, actors.findLocalByUsername(ADMIN).orElseThrow().id());
        user = actors.findLocalByUsername(USER).orElseThrow();
    }

    @Test
    void anAdminAddsChangesAndRemovesEmojiOnThePage() throws Exception {
        var session = TestOAuth.logIn(mockMvc, ADMIN, PASSWORD);
        var shortcode = shortcode();

        mockMvc.perform(multipart(PAGE).file(image()).session(session).with(csrf())
                        .param("shortcode", shortcode).param("category", "Blobs"))
                .andExpect(redirectedUrl(PAGE));
        var added = findLocal(shortcode);
        assertThat(added.category()).isEqualTo("Blobs");
        assertThat(added.visibleInPicker()).isTrue();
        assertThat(media.directory().resolve(added.imageName())).exists();
        mockMvc.perform(get(PAGE).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(":" + shortcode + ":")));

        mockMvc.perform(post(PAGE + "/" + added.id()).session(session).with(csrf()).param("category", ""))
                .andExpect(redirectedUrl(PAGE));
        var changed = findLocal(shortcode);
        assertThat(changed.category()).isNull();
        assertThat(changed.visibleInPicker()).isFalse();

        mockMvc.perform(post(PAGE + "/" + added.id() + "/delete").session(session).with(csrf()))
                .andExpect(redirectedUrl(PAGE));
        assertThat(emojis.findLocal(added.id())).isEmpty();
        assertThat(media.directory().resolve(added.imageName())).doesNotExist();
    }

    @Test
    void anAdminRenamesClearsAndMovesCategoriesOnThePage() throws Exception {
        var session = TestOAuth.logIn(mockMvc, ADMIN, PASSWORD);
        var blobs = "Blobs " + shortcode();
        var cats = "Cats " + shortcode();
        var first = emojis.addLocal(shortcode(), TestImages.png(64, 64), blobs);
        var second = emojis.addLocal(shortcode(), TestImages.png(64, 64), blobs);
        var loose = emojis.addLocal(shortcode(), TestImages.png(64, 64), null);

        var page = mockMvc.perform(get(PAGE).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("<h3>" + blobs + " (2)</h3>", "<option value=\"" + blobs + "\">");
        assertThat(page.indexOf("<h3>" + blobs)).isLessThan(page.indexOf("<h3>Uncategorized"));
        assertThat(page.indexOf("<h3>Uncategorized")).isLessThan(page.indexOf(":" + loose.shortcode() + ":"));

        mockMvc.perform(post(PAGE + "/categories").session(session).with(csrf())
                        .param("from", blobs).param("to", " " + cats + " "))
                .andExpect(redirectedUrl(PAGE));
        assertThat(findLocal(first.shortcode()).category()).isEqualTo(cats);
        assertThat(findLocal(second.shortcode()).category()).isEqualTo(cats);
        assertThat(findLocal(loose.shortcode()).category()).isNull();

        mockMvc.perform(post(PAGE + "/move").session(session).with(csrf())
                        .param("ids", loose.id().toString(), "not an id", UUID.randomUUID().toString())
                        .param("category", cats))
                .andExpect(redirectedUrl(PAGE));
        assertThat(findLocal(loose.shortcode()).category()).isEqualTo(cats);

        mockMvc.perform(post(PAGE + "/move").session(session).with(csrf())
                        .param("ids", first.id().toString()).param("category", ""))
                .andExpect(redirectedUrl(PAGE));
        assertThat(findLocal(first.shortcode()).category()).isNull();

        mockMvc.perform(post(PAGE + "/categories").session(session).with(csrf())
                        .param("from", cats).param("to", ""))
                .andExpect(redirectedUrl(PAGE));
        assertThat(findLocal(second.shortcode()).category()).isNull();
        assertThat(findLocal(loose.shortcode()).category()).isNull();

        mockMvc.perform(post(PAGE + "/move").session(session).with(csrf()).param("category", cats))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("Choose the emoji to move")));
        mockMvc.perform(post(PAGE + "/categories").session(session).with(csrf())
                        .param("from", cats).param("to", "x".repeat(CustomEmojis.MAX_CATEGORY_LENGTH + 1)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("longer than " + CustomEmojis.MAX_CATEGORY_LENGTH)));
    }

    @Test
    void uncategorizedIsReservedInAnyCase() throws Exception {
        var session = TestOAuth.logIn(mockMvc, ADMIN, PASSWORD);
        var category = "Reserved " + shortcode();
        var emoji = emojis.addLocal(shortcode(), TestImages.png(64, 64), category);

        assertThatThrownBy(() -> emojis.addLocal(shortcode(), TestImages.png(64, 64), "Uncategorized"))
                .isInstanceOf(InvalidCustomEmojiException.class);
        mockMvc.perform(multipart(PAGE).file(image()).session(session).with(csrf())
                        .param("shortcode", shortcode()).param("category", " uncategorized "))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("is reserved")));
        mockMvc.perform(post(PAGE + "/" + emoji.id()).session(session).with(csrf())
                        .param("category", "UNCATEGORIZED").param("visibleInPicker", "true"))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(post(PAGE + "/categories").session(session).with(csrf())
                        .param("from", category).param("to", "Uncategorized"))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(post(PAGE + "/move").session(session).with(csrf())
                        .param("ids", emoji.id().toString()).param("category", "Uncategorized"))
                .andExpect(status().isUnprocessableContent());
        assertThat(findLocal(emoji.shortcode()).category()).isEqualTo(category);
    }

    @Test
    void thePageRefusesBadShortcodesDuplicatesAndImages() throws Exception {
        var session = TestOAuth.logIn(mockMvc, ADMIN, PASSWORD);
        var shortcode = shortcode();
        emojis.addLocal(shortcode, TestImages.png(64, 64), null);

        mockMvc.perform(multipart(PAGE).file(image()).session(session).with(csrf()).param("shortcode", "a b"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("2 to 100 letters")));
        mockMvc.perform(multipart(PAGE).file(image()).session(session).with(csrf()).param("shortcode", shortcode))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("already an emoji")));
        mockMvc.perform(multipart(PAGE)
                        .file(new MockMultipartFile("image", "e.png", "image/png", "not an image".getBytes()))
                        .session(session).with(csrf()).param("shortcode", shortcode()))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(multipart(PAGE)
                        .file(new MockMultipartFile("image", "e.png", "image/png", new byte[210 * 1024]))
                        .session(session).with(csrf()).param("shortcode", shortcode()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("larger than 200 KB")));
        mockMvc.perform(get(PAGE).session(session))
                .andExpect(content().string(containsString("of up to <span>200 KB</span>")));
    }

    @Test
    void onlyAdminsCanUseThePage() throws Exception {
        var session = TestOAuth.logIn(mockMvc, USER, PASSWORD);

        mockMvc.perform(get(PAGE).session(session)).andExpect(status().isForbidden());
        mockMvc.perform(multipart(PAGE).file(image()).session(session).with(csrf()).param("shortcode", shortcode()))
                .andExpect(status().isForbidden());
    }

    @Test
    void listsTheServersEmojiWithoutAToken() throws Exception {
        var emoji = emojis.addLocal(shortcode(), TestImages.png(64, 64), "Blobs");

        mockMvc.perform(get("/api/v1/custom_emojis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.shortcode == '%s')].url".formatted(emoji.shortcode()),
                        hasItem(startsWith("https://emoji.example/media/"))))
                .andExpect(jsonPath("$[?(@.shortcode == '%s')].static_url".formatted(emoji.shortcode())).isNotEmpty())
                .andExpect(jsonPath("$[?(@.shortcode == '%s')].visible_in_picker".formatted(emoji.shortcode()),
                        hasItem(true)))
                .andExpect(jsonPath("$[?(@.shortcode == '%s')].category".formatted(emoji.shortcode()),
                        hasItem("Blobs")));
    }

    @Test
    void anAnimatedEmojiHasAStillFrame() throws Exception {
        var emoji = emojis.addLocal(shortcode(), TestImages.animatedGif(3, 32, 32), null);

        assertThat(emoji.staticName()).isNotEqualTo(emoji.imageName());
    }

    @Test
    void refusesAnImageThatsTooLarge() {
        assertThatThrownBy(() -> emojis.checkImageSize(emojis.maxImageSize().toBytes() + 1))
                .isInstanceOf(InvalidCustomEmojiException.class);
    }

    @Test
    void aStatusShowsAndSendsTheEmojiItNames() throws Exception {
        var blob = emojis.addLocal(shortcode(), TestImages.png(64, 64), null);
        var cat = emojis.addLocal(shortcode(), TestImages.png(64, 64), null);
        var status = statuses.create(user.id(), new NewStatus(
                "Hi :%s: and :unknown_emoji:".formatted(blob.shortcode()), ":%s:".formatted(cat.shortcode()), null,
                Visibility.PUBLIC, false, null));

        mockMvc.perform(get("/api/v1/statuses/" + status.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", containsString(":" + blob.shortcode() + ":")))
                .andExpect(jsonPath("$.emojis", hasSize(2)))
                .andExpect(jsonPath("$.emojis[*].shortcode").value(org.hamcrest.Matchers.containsInAnyOrder(
                        blob.shortcode(), cat.shortcode())));

        mockMvc.perform(get(status.uri().getRawPath()).header(HttpHeaders.ACCEPT, ACTIVITY_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['@context'][1].Emoji").value("toot:Emoji"))
                .andExpect(jsonPath("$.tag[?(@.type == 'Emoji')]", hasSize(2)))
                .andExpect(jsonPath("$.tag[?(@.name == ':%s:')].id".formatted(blob.shortcode()),
                        hasItem(blob.uri().toString())))
                .andExpect(jsonPath("$.tag[?(@.name == ':%s:')].icon.mediaType".formatted(blob.shortcode()),
                        hasItem("image/webp")));

        mockMvc.perform(get(blob.uri().getRawPath()).header(HttpHeaders.ACCEPT, ACTIVITY_JSON)
                        .header(HttpHeaders.ORIGIN, "https://other.example"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                .andExpect(jsonPath("$.type").value("Emoji"))
                .andExpect(jsonPath("$.id").value(blob.uri().toString()))
                .andExpect(jsonPath("$.name").value(":" + blob.shortcode() + ":"))
                .andExpect(jsonPath("$.icon.url", startsWith("https://emoji.example/media/")));
        mockMvc.perform(get("/emojis/" + UUID.randomUUID()).header(HttpHeaders.ACCEPT, ACTIVITY_JSON))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(status.uri().getRawPath()).header(HttpHeaders.ACCEPT, "text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"custom-emoji\"")))
                .andExpect(content().string(containsString("alt=\":" + blob.shortcode() + ":\"")));
    }

    @Test
    void aPollHasTheEmojiOfItsOptions() throws Exception {
        var blob = emojis.addLocal(shortcode(), TestImages.png(64, 64), null);
        var cat = emojis.addLocal(shortcode(), TestImages.png(64, 64), null);
        var status = statuses.create(user.id(), new NewStatus(
                "Which? :" + cat.shortcode() + ":", "", null, Visibility.PUBLIC, false, null, List.of(), null, null,
                new NewPoll(List.of(":" + blob.shortcode() + ":", "Neither"), 3600, false, false)));

        mockMvc.perform(get("/api/v1/statuses/" + status.id()))
                .andExpect(jsonPath("$.emojis", hasSize(2)))
                .andExpect(jsonPath("$.poll.emojis", hasSize(1)))
                .andExpect(jsonPath("$.poll.emojis[0].shortcode").value(blob.shortcode()));
    }

    @Test
    void eachVersionKeepsItsOwnEmoji() throws Exception {
        var blob = emojis.addLocal(shortcode(), TestImages.png(64, 64), null);
        var cat = emojis.addLocal(shortcode(), TestImages.png(64, 64), null);
        var status = statuses.create(user.id(), new NewStatus(
                "First :" + blob.shortcode() + ":", "", null, Visibility.PUBLIC, false, null));

        statuses.edit(status.id(), user.id(), new StatusEdit("Then :" + cat.shortcode() + ":", "", null, null))
                .orElseThrow();

        mockMvc.perform(get("/api/v1/statuses/" + status.id()))
                .andExpect(jsonPath("$.emojis", hasSize(1)))
                .andExpect(jsonPath("$.emojis[0].shortcode").value(cat.shortcode()));
        mockMvc.perform(get("/api/v1/statuses/" + status.id() + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].emojis[0].shortcode").value(blob.shortcode()))
                .andExpect(jsonPath("$[1].emojis[0].shortcode").value(cat.shortcode()));

        // Removing an emoji takes it out of every version.
        emojis.deleteLocal(blob.id());
        mockMvc.perform(get("/api/v1/statuses/" + status.id() + "/history"))
                .andExpect(jsonPath("$[0].emojis", hasSize(0)));
    }

    @Test
    void aProfileShowsAndSendsTheEmojiItNames() throws Exception {
        var blob = emojis.addLocal(shortcode(), TestImages.png(64, 64), null);
        var cat = emojis.addLocal(shortcode(), TestImages.png(64, 64), null);
        var account = newAccount();
        profiles.update(account.id(), ProfileChange.NONE
                .withDisplayName("Me :" + blob.shortcode() + ":")
                .withNote("Bio :" + cat.shortcode() + ":")
                .withFields(List.of(new ProfileChange.NewField("Likes", ":" + blob.shortcode() + ":"))));

        mockMvc.perform(get("/api/v1/accounts/" + account.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emojis", hasSize(2)))
                .andExpect(jsonPath("$.emojis[*].shortcode").value(org.hamcrest.Matchers.containsInAnyOrder(
                        blob.shortcode(), cat.shortcode())));

        mockMvc.perform(get("/users/" + account.username()).header(HttpHeaders.ACCEPT, ACTIVITY_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['@context'][2].Emoji").value("toot:Emoji"))
                .andExpect(jsonPath("$.tag[?(@.type == 'Emoji')]", hasSize(2)));

        mockMvc.perform(get("/users/" + account.username()).header(HttpHeaders.ACCEPT, "text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("alt=\":" + cat.shortcode() + ":\"")))
                .andExpect(content().string(not(containsString(">:" + blob.shortcode() + ":<"))));
    }

    private CustomEmoji findLocal(String shortcode) {
        return emojis.listLocal().stream().filter(emoji -> emoji.shortcode().equals(shortcode)).findFirst()
                .orElseThrow();
    }

    private static MockMultipartFile image() throws Exception {
        return new MockMultipartFile("image", "emoji.png", "image/png", TestImages.png(64, 64));
    }

    private static String shortcode() {
        return "e" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private Actor newAccount() {
        var name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return actors.createLocalIfAbsent(name, actor -> {
        }).orElseThrow();
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
}
