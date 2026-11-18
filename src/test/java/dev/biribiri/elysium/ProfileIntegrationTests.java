package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.ProfileChange;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.actor.RemoteProfile;
import dev.biribiri.elysium.content.CustomEmojis;
import dev.biribiri.elysium.content.RemoteEmoji;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Editing profiles through the client API and the settings page, and how they're shown. No job worker runs. What other
 * servers are told is tested in {@code ProfileFederationIntegrationTests}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://profiles.example",
        "elysium.jobs.worker-enabled=false",
        "elysium.profiles.max-fields=2",
        "elysium.profiles.max-image-size=200KB"
})
@AutoConfigureMockMvc
class ProfileIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String UPDATE = "/api/v1/accounts/update_credentials";

    /**
     * Logging in is slow because of Argon2, so each account's token is only created once.
     */
    private static final Map<String, String> TOKENS = new HashMap<>();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;
    @Autowired
    private ProfileService profiles;
    @Autowired
    private CustomEmojis emojis;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() throws Exception {
        transaction = new TransactionTemplate(transactionManager);
        TestAccounts.makeSureAnAccountExists(actors);
        token("alice");
        var alice = account("alice");
        profiles.update(alice.id(), ProfileChange.NONE
                .withDisplayName("")
                .withNote("")
                .withFields(List.of())
                .withAvatar(ProfileChange.Image.REMOVE)
                .withHeader(ProfileChange.Image.REMOVE));
    }

    // The client API

    @Test
    void changesTheProfileFromAForm() throws Exception {
        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("display_name", "Alice <3")
                        .param("note", "Hello\n\nSee https://alice.example")
                        .param("fields_attributes[0][name]", "Site")
                        .param("fields_attributes[0][value]", "https://alice.example")
                        .param("fields_attributes[1][name]", "Pronouns")
                        .param("fields_attributes[1][value]", "she/her"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.display_name").value("Alice <3"))
                .andExpect(jsonPath("$.note").value(containsString("<p>Hello</p><p>See <a href=\"https://alice.example\"")))
                .andExpect(jsonPath("$.fields", hasSize(2)))
                .andExpect(jsonPath("$.fields[0].name").value("Site"))
                .andExpect(jsonPath("$.fields[0].value").value(containsString("rel=\"me nofollow noopener noreferrer\"")))
                .andExpect(jsonPath("$.fields[0].verified_at").isEmpty())
                .andExpect(jsonPath("$.fields[1].value").value("she/her"))
                .andExpect(jsonPath("$.source.note").value("Hello\n\nSee https://alice.example"))
                .andExpect(jsonPath("$.source.fields[0].value").value("https://alice.example"));

        mockMvc.perform(get("/api/v1/accounts/" + account("alice").id()))
                .andExpect(jsonPath("$.display_name").value("Alice <3"))
                .andExpect(jsonPath("$.fields", hasSize(2)));
    }

    @Test
    void readsFieldsInEachShapeClientsSend() throws Exception {
        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields_attributes\":[{\"name\":\"a\",\"value\":\"1\"},{\"name\":\"b\",\"value\":\"2\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[1].name").value("b"));

        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields_attributes\":{\"1\":{\"name\":\"second\",\"value\":\"2\"},"
                                + "\"0\":{\"name\":\"first\",\"value\":\"1\"}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[0].name").value("first"))
                .andExpect(jsonPath("$.fields[1].name").value("second"));

        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("fields_attributes[][name]", "x", "y")
                        .param("fields_attributes[][value]", "1, 2", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[0].value").value("1, 2"))
                .andExpect(jsonPath("$.fields[1].name").value("y"));

        // Blank rows are how fields are removed.
        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("fields_attributes[0][name]", "")
                        .param("fields_attributes[0][value]", "")
                        .param("fields_attributes[1][name]", "kept")
                        .param("fields_attributes[1][value]", "yes"))
                .andExpect(jsonPath("$.fields", hasSize(1)))
                .andExpect(jsonPath("$.fields[0].name").value("kept"));
    }

    @Test
    void leavesOutWhatIsntSentAndClearsWhatsEmpty() throws Exception {
        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"display_name\":\"Alice\",\"note\":\"Bio\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("hide_collections", "false"))
                .andExpect(jsonPath("$.display_name").value("Alice"))
                .andExpect(jsonPath("$.note").value("<p>Bio</p>"));

        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("note", ""))
                .andExpect(jsonPath("$.display_name").value("Alice"))
                .andExpect(jsonPath("$.note").value(""));
    }

    @Test
    void refusesWhatBreaksALimitAndChangesNothing() throws Exception {
        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("display_name", "x".repeat(ProfileService.MAX_DISPLAY_NAME + 1))
                        .param("note", "changed"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("Display name")));
        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields_attributes\":[{\"name\":\"a\",\"value\":\"1\"},{\"name\":\"b\",\"value\":\"2\"},"
                                + "{\"name\":\"c\",\"value\":\"3\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("2")));
        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields_attributes\":\"nope\"}"))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"display_name\":42}"))
                .andExpect(status().isUnprocessableContent());

        assertThat(profiles.find(account("alice").id()).note()).isEmpty();
    }

    @Test
    void acceptsSettingsItDoesntHaveOnlyWithTheValuesItReports() throws Exception {
        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("display_name", "Alice")
                        .param("locked", "false")
                        .param("bot", "0")
                        .param("discoverable", "false")
                        .param("source[privacy]", "public")
                        .param("source[language]", ""))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locked\":false,\"source\":{\"privacy\":\"public\",\"sensitive\":false}}"))
                .andExpect(status().isOk());

        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("display_name", "Changed")
                        .param("bot", "true"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("bot")));
        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":{\"privacy\":\"private\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("source[privacy]")));
        assertThat(profiles.find(account("alice").id()).displayName()).isEqualTo("Alice");
    }

    @Test
    void changesWhetherAnyonesSearchCanFindTheAccountsStatuses() throws Exception {
        var alice = account("alice");
        mockMvc.perform(get("/api/v1/accounts/" + alice.id())).andExpect(jsonPath("$.indexable").value(false));

        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("indexable", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexable").value(true));
        mockMvc.perform(get("/users/alice").accept("application/activity+json"))
                .andExpect(jsonPath("$.indexable").value(true))
                .andExpect(jsonPath("$['@context'][2].indexable").value("toot:indexable"));

        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_JSON).content("{\"indexable\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexable").value(false));
        assertThat(profiles.find(alice.id()).indexable()).isFalse();
    }

    @Test
    void theSettingsPageChangesWhetherTheAccountIsIndexable() throws Exception {
        var session = TestOAuth.logIn(mockMvc, "alice", PASSWORD);
        mockMvc.perform(get("/settings").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"indexable\"")));

        mockMvc.perform(post("/settings").session(session).with(csrf()).param("indexable", "true"))
                .andExpect(redirectedUrl("/settings?saved"));
        assertThat(profiles.find(account("alice").id()).indexable()).isTrue();

        mockMvc.perform(post("/settings").session(session).with(csrf()))
                .andExpect(redirectedUrl("/settings?saved"));
        assertThat(profiles.find(account("alice").id()).indexable()).isFalse();
    }

    @Test
    void uploadsReplacesAndRemovesImages() throws Exception {
        var first = uploadAvatar(TestImages.png(64, 64));
        assertThat(first).startsWith("https://profiles.example/media/").endsWith(".webp");
        mockMvc.perform(get(URI.create(first).getPath()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/webp"))
                .andExpect(header().string("Cache-Control", containsString("immutable")));

        var second = uploadAvatar(TestImages.png(32, 32));
        assertThat(second).isNotEqualTo(first);
        mockMvc.perform(get(URI.create(first).getPath())).andExpect(status().isNotFound());

        mockMvc.perform(withToken(delete("/api/v1/profile/avatar")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatar").value(endsWith("/images/missing.png")));
        mockMvc.perform(get(URI.create(second).getPath())).andExpect(status().isNotFound());
        mockMvc.perform(withToken(delete("/api/v1/profile/header"))).andExpect(status().isOk());
    }

    @Test
    void anAnimatedAvatarHasAStillVersion() throws Exception {
        var gif = TestImages.animatedGif(3, 40, 40);

        mockMvc.perform(withToken(multipart(HttpMethod.PATCH, UPDATE).file(new MockMultipartFile("avatar", "a.gif", "image/gif", gif))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatar").value(endsWith(".webp")))
                .andExpect(jsonPath("$.avatar_static").value(endsWith(".webp")))
                .andExpect(result -> {
                    var body = result.getResponse().getContentAsString();
                    assertThat((String) JsonPath.read(body, "$.avatar")).isNotEqualTo(JsonPath.read(body, "$.avatar_static"));
                });
    }

    @Test
    void refusesWhatCantBeAnImage() throws Exception {
        mockMvc.perform(withToken(multipart(HttpMethod.PATCH, UPDATE)
                        .file(new MockMultipartFile("avatar", "a.png", "image/png", "not an image".getBytes()))
                        .param("display_name", "Changed")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("Avatar")));
        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"avatar\":\"data:image/png;base64,AAAA\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("multipart")));
        assertThat(profiles.find(account("alice").id()).displayName()).isEmpty();
    }

    /**
     * Tomcat refuses a file over the limit before it's read, which MockMvc doesn't, so this is the check after it's read.
     */
    @Test
    void refusesAnImageOverTheLimit() throws Exception {
        mockMvc.perform(withToken(multipart(HttpMethod.PATCH, UPDATE)
                        .file(new MockMultipartFile("header", "h.png", "image/png", new byte[300 * 1024]))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("larger than")));
    }

    @Test
    void theInstanceSaysHowManyFieldsThereCanBe() throws Exception {
        mockMvc.perform(get("/api/v1/instance"))
                .andExpect(jsonPath("$.configuration.accounts.max_profile_fields").value(2));
    }

    // Pages and documents

    // Accounts on other servers

    @Test
    void showsTheProfileOfAnAccountOnAnotherServer() throws Exception {
        var host = "r" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/bob");
        var avatar = URI.create("https://media." + host + "/avatar.png");
        var header = URI.create("https://media." + host + "/header.png");
        var profile = new RemoteProfile(
                "Bob :blob:", "Hello", "<p>Hello</p>", avatar, header,
                List.of(new RemoteProfile.Field("Site", "bob.test", "<a href=\"https://bob.test\">bob.test</a>")),
                true, false, true, false, Instant.parse("2020-03-04T05:06:07Z"), URI.create("https://" + host + "/@bob"));
        var bob = transaction.execute(status -> {
            var stored = actors.upsertRemote(new RemoteActorProfile(uri, "bob", host, URI.create(uri + "/inbox"), null,
                    null, null, null, null, profile));
            var ids = emojis.storeRemote(host, List.of(
                    new RemoteEmoji("blob", null, URI.create("https://media." + host + "/blob.png"), null),
                    new RemoteEmoji("unused", null, URI.create("https://media." + host + "/unused.png"), null)));
            emojis.replaceForRemoteProfile(stored.id(), ids.values());
            return stored;
        });
        assertThat(profiles.cacheRemoteImage(bob.id(), true, header, TestImages.png(64, 32))).isTrue();

        mockMvc.perform(get("/api/v1/accounts/" + bob.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acct").value("bob@" + host))
                .andExpect(jsonPath("$.display_name").value("Bob :blob:"))
                .andExpect(jsonPath("$.note").value("<p>Hello</p>"))
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.bot").value(true))
                .andExpect(jsonPath("$.group").value(false))
                .andExpect(jsonPath("$.created_at").value("2020-03-04T00:00:00Z"))
                .andExpect(jsonPath("$.url").value("https://" + host + "/@bob"))
                .andExpect(jsonPath("$.uri").value(uri.toString()))
                // Not downloaded yet, so it's the proxy, which downloads it.
                .andExpect(jsonPath("$.avatar").value("https://profiles.example/media_proxy/accounts/" + bob.id() + "/avatar"))
                .andExpect(jsonPath("$.avatar_static")
                        .value("https://profiles.example/media_proxy/accounts/" + bob.id() + "/avatar/static"))
                .andExpect(jsonPath("$.header").value(startsWith("https://profiles.example/media/")))
                .andExpect(jsonPath("$.fields", hasSize(1)))
                .andExpect(jsonPath("$.fields[0].value").value("<a href=\"https://bob.test\">bob.test</a>"))
                .andExpect(jsonPath("$.fields[0].verified_at").isEmpty())
                .andExpect(jsonPath("$.emojis", hasSize(1)))
                .andExpect(jsonPath("$.emojis[0].shortcode").value("blob"));
    }

    @Test
    void anOldCopyOfAnAccountOnAnotherServersAvatarIsRemovedAndDownloadedAgainLater() throws Exception {
        var host = "r" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/carol");
        var avatar = URI.create("https://media." + host + "/avatar.png");
        var header = URI.create("https://media." + host + "/header.png");
        var profile = new RemoteProfile("Carol", "", "", avatar, header, List.of(), false, false, false, false, null,
                null);
        var carol = transaction.execute(status -> actors.upsertRemote(new RemoteActorProfile(
                uri, "carol", host, URI.create(uri + "/inbox"), null, null, null, null, null, profile)));
        assertThat(profiles.cacheRemoteImage(carol.id(), false, avatar, TestImages.png(64, 64))).isTrue();
        assertThat(profiles.cacheRemoteImage(carol.id(), true, header, TestImages.png(64, 32))).isTrue();
        var copy = JsonPath.<String>read(mockMvc.perform(get("/api/v1/accounts/" + carol.id()))
                .andReturn().getResponse().getContentAsString(), "$.avatar");
        assertThat(copy).startsWith("https://profiles.example/media/");
        jdbc.sql("UPDATE profile SET avatar_cached_at = now() - interval '31 days' WHERE actor_id = :id")
                .param("id", carol.id()).update();

        profiles.pruneRemoteImages(Instant.now().minus(Duration.ofDays(30)));

        mockMvc.perform(get("/api/v1/accounts/" + carol.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatar").value("https://profiles.example/media_proxy/accounts/" + carol.id() + "/avatar"))
                .andExpect(jsonPath("$.avatar_static")
                        .value("https://profiles.example/media_proxy/accounts/" + carol.id() + "/avatar/static"))
                .andExpect(jsonPath("$.header").value(startsWith("https://profiles.example/media/")));
        mockMvc.perform(get(URI.create(copy).getPath())).andExpect(status().isNotFound());
        assertThat(profiles.findImageToDownload(carol.id(), false)).contains(avatar);
        assertThat(profiles.findImageToDownload(carol.id(), true)).isEmpty();
    }

    @Test
    void aNewAvatarOfAnAccountOnAnotherServerIsKeptForTheWholeRetention() throws Exception {
        var host = "r" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/dave");
        var first = URI.create("https://media." + host + "/first.png");
        var second = URI.create("https://media." + host + "/second.png");
        var dave = transaction.execute(status -> actors.upsertRemote(new RemoteActorProfile(
                uri, "dave", host, URI.create(uri + "/inbox"), null, null, null, null, null, remoteProfile(first))));
        assertThat(profiles.cacheRemoteImage(dave.id(), false, first, TestImages.png(64, 64))).isTrue();
        jdbc.sql("UPDATE profile SET avatar_cached_at = now() - interval '31 days' WHERE actor_id = :id")
                .param("id", dave.id()).update();

        transaction.executeWithoutResult(status -> profiles.replaceRemote(dave.id(), remoteProfile(first)));
        assertThat(avatarCachedAt(dave.id()).orElseThrow()).isBefore(Instant.now().minus(Duration.ofDays(30)));

        transaction.executeWithoutResult(status -> profiles.replaceRemote(dave.id(), remoteProfile(second)));
        assertThat(avatarCachedAt(dave.id())).isEmpty();

        assertThat(profiles.cacheRemoteImage(dave.id(), false, second, TestImages.png(32, 32))).isTrue();
        profiles.pruneRemoteImages(Instant.now().minus(Duration.ofDays(30)));
        assertThat(avatarCachedAt(dave.id()).orElseThrow()).isAfter(Instant.now().minus(Duration.ofMinutes(1)));
        assertThat(profiles.findImageToDownload(dave.id(), false)).isEmpty();
    }

    private static RemoteProfile remoteProfile(URI avatar) {
        return new RemoteProfile("Dave", "", "", avatar, null, List.of(), false, false, false, false, null, null);
    }

    private Optional<Instant> avatarCachedAt(UUID actorId) {
        return jdbc.sql("SELECT avatar_cached_at FROM profile WHERE actor_id = :id")
                .param("id", actorId)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    @Test
    void anAccountOnAnotherServerWithNoNameShowsItsUsername() throws Exception {
        var host = "r" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/bob");
        var bob = actors.upsertRemote(new RemoteActorProfile(uri, "bob", host, URI.create(uri + "/inbox"), null, null, null));

        mockMvc.perform(get("/api/v1/accounts/" + bob.id()))
                .andExpect(jsonPath("$.display_name").value("bob"))
                .andExpect(jsonPath("$.note").value(""))
                .andExpect(jsonPath("$.avatar").value(endsWith("/images/missing.png")))
                .andExpect(jsonPath("$.url").value(uri.toString()));
    }

    @Test
    void showsTheProfileOnItsPageAndInItsActorDocument() throws Exception {
        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("display_name", "Alice <b>")
                        .param("note", "<script>x</script>")
                        .param("fields_attributes[0][name]", "Site")
                        .param("fields_attributes[0][value]", "https://alice.example"))
                .andExpect(status().isOk());
        uploadAvatar(TestImages.png(10, 10));

        mockMvc.perform(get("/users/alice").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Alice &lt;b&gt;")))
                .andExpect(content().string(containsString("&lt;script&gt;x&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>x"))))
                .andExpect(content().string(containsString("rel=\"me nofollow noopener noreferrer\"")))
                .andExpect(content().string(containsString("/media/")));

        mockMvc.perform(get("/users/alice").accept("application/activity+json"))
                .andExpect(jsonPath("$.name").value("Alice <b>"))
                .andExpect(jsonPath("$.summary").value("<p>&lt;script&gt;x&lt;/script&gt;</p>"))
                .andExpect(jsonPath("$.icon.type").value("Image"))
                .andExpect(jsonPath("$.icon.mediaType").value("image/webp"))
                .andExpect(jsonPath("$.image").doesNotExist())
                .andExpect(jsonPath("$.attachment[0].type").value("PropertyValue"))
                .andExpect(jsonPath("$.attachment[0].name").value("Site"))
                .andExpect(jsonPath("$['@context'][2].PropertyValue").value("schema:PropertyValue"))
                .andExpect(jsonPath("$.published").exists())
                .andExpect(jsonPath("$.updated").exists());
    }

    @Test
    void aSuspendedAccountsDocumentLeavesOutItsProfile() throws Exception {
        var carol = account("carol");
        profiles.update(carol.id(), ProfileChange.NONE.withDisplayName("Carol").withNote("Bio"));
        actors.suspend(carol.id());
        try {
            mockMvc.perform(get("/users/carol").accept("application/activity+json"))
                    .andExpect(jsonPath("$.suspended").value(true))
                    .andExpect(jsonPath("$.name").doesNotExist())
                    .andExpect(jsonPath("$.summary").doesNotExist());
        } finally {
            actors.unsuspend(carol.id());
        }
    }

    // The settings page

    @Test
    void changesTheProfileFromTheSettingsPage() throws Exception {
        var session = TestOAuth.logIn(mockMvc, "alice", PASSWORD);

        mockMvc.perform(get("/settings").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"displayName\"")))
                .andExpect(content().string(containsString("Fields (up to 2)")));

        mockMvc.perform(multipart("/settings").file(new MockMultipartFile("avatar", "a.png", "image/png", TestImages.png(20, 20)))
                        .session(session).with(csrf())
                        .param("displayName", "Alice, from the page")
                        .param("note", "Bio")
                        .param("fieldName", "Site", "")
                        .param("fieldValue", "https://a.example, with a comma", ""))
                .andExpect(redirectedUrl("/settings?saved"));

        var profile = profiles.find(account("alice").id());
        assertThat(profile.displayName()).isEqualTo("Alice, from the page");
        assertThat(profile.fields()).hasSize(1);
        assertThat(profile.fields().getFirst().value()).isEqualTo("https://a.example, with a comma");
        assertThat(profile.avatar()).isNotNull();

        mockMvc.perform(post("/settings").session(session).with(csrf()).param("removeAvatar", "true"))
                .andExpect(redirectedUrl("/settings?saved"));
        assertThat(profiles.find(account("alice").id()).avatar()).isNull();
        assertThat(profiles.find(account("alice").id()).displayName()).isEqualTo("Alice, from the page");
    }

    @Test
    void theSettingsPageShowsWhatsWrongAndKeepsWhatWasTyped() throws Exception {
        var session = TestOAuth.logIn(mockMvc, "alice", PASSWORD);

        mockMvc.perform(post("/settings").session(session).with(csrf())
                        .param("displayName", "Typed")
                        .param("note", "x".repeat(ProfileService.MAX_NOTE + 1)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("Bio is too long")))
                .andExpect(content().string(containsString("value=\"Typed\"")));
        assertThat(profiles.find(account("alice").id()).displayName()).isEmpty();
    }

    private String uploadAvatar(byte[] image) throws Exception {
        var body = mockMvc.perform(withToken(multipart(HttpMethod.PATCH, UPDATE)
                        .file(new MockMultipartFile("avatar", "a.png", "image/png", image))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.avatar");
    }

    private Actor account(String username) throws Exception {
        if (actors.findLocalByUsername(username).isEmpty()) {
            token(username);
        }
        return actors.findLocalByUsername(username).orElseThrow();
    }

    private <B extends AbstractMockHttpServletRequestBuilder<B>> B withToken(B request) throws Exception {
        return request.header("Authorization", "Bearer " + token("alice"));
    }

    private String token(String username) throws Exception {
        var cached = TOKENS.get(username);
        if (cached != null) {
            return cached;
        }
        mockMvc.perform(post("/signup").with(csrf())
                        .param("invite", TestAccounts.invite(jdbc))
                        .param("username", username)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(status().is3xxRedirection());
        var token = TestOAuth.userToken(mockMvc, username, PASSWORD, "read write");
        TOKENS.put(username, token);
        return token;
    }
}
