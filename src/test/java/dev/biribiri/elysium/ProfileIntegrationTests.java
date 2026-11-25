package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.Profile;
import dev.biribiri.elysium.actor.ProfileChange;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.actor.ProfileUpdated;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
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
import java.util.function.Function;

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
@RecordApplicationEvents
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
    @Autowired
    private ApplicationEvents events;

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
                .withHeader(ProfileChange.Image.REMOVE)
                .withBackground(ProfileChange.Image.REMOVE));
    }

    // The client API

    /**
     * Its own account, since the quote policy it changes isn't reset between tests.
     */
    @Test
    void preferencesReportTheDefaultsAndTheQuotePolicy() throws Exception {
        var bearer = "Bearer " + token("prefs");
        mockMvc.perform(get("/api/v1/preferences").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['posting:default:visibility']").value("public"))
                .andExpect(jsonPath("$['posting:default:sensitive']").value(false))
                .andExpect(content().string(containsString("\"posting:default:language\":null")))
                .andExpect(jsonPath("$['posting:default:quote_policy']").value("public"))
                .andExpect(jsonPath("$['posting:default:spoiler_field']").doesNotExist())
                .andExpect(jsonPath("$['reading:expand:media']").value("default"))
                .andExpect(jsonPath("$['reading:expand:spoilers']").value(false))
                .andExpect(jsonPath("$['reading:autoplay:gifs']").value(false));

        mockMvc.perform(patch(UPDATE).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("source[quote_policy]", "followers"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/preferences").header("Authorization", bearer))
                .andExpect(jsonPath("$['posting:default:quote_policy']").value("followers"));
    }

    @Test
    void preferencesNeedReadAccounts() throws Exception {
        account("alice");
        var token = TestOAuth.userToken(mockMvc, "alice", PASSWORD, "write");
        mockMvc.perform(get("/api/v1/preferences").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/preferences")).andExpect(status().isUnauthorized());
    }

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
                        .param("source[privacy]", "public")
                        .param("source[sensitive]", "false")
                        .param("source[language]", ""))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locked\":false,\"source\":{\"privacy\":\"public\",\"sensitive\":false}}"))
                .andExpect(status().isOk());

        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("display_name", "Changed")
                        .param("source[sensitive]", "true"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("source[sensitive]")));
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
    void changesWhetherTheAccountIsInTheDirectory() throws Exception {
        var alice = account("alice");
        mockMvc.perform(get("/api/v1/accounts/" + alice.id())).andExpect(jsonPath("$.discoverable").value(false));

        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("discoverable", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discoverable").value(true))
                .andExpect(jsonPath("$.source.discoverable").value(true));
        mockMvc.perform(get("/users/alice").accept("application/activity+json"))
                .andExpect(jsonPath("$.discoverable").value(true))
                .andExpect(jsonPath("$['@context'][2].discoverable").value("toot:discoverable"));

        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"discoverable\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discoverable").value(false));
        assertThat(profiles.find(alice.id()).discoverable()).isFalse();

        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":{\"privacy\":\"public\",\"discoverable\":true}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source.discoverable").value(true));
        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("source[discoverable]", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discoverable").value(false));
    }

    @Test
    void marksTheAccountAsABotOrACat() throws Exception {
        var alice = account("alice");
        mockMvc.perform(get("/api/v1/accounts/" + alice.id()))
                .andExpect(jsonPath("$.bot").value(false))
                .andExpect(jsonPath("$.is_cat").value(false));
        mockMvc.perform(get("/users/alice").accept("application/activity+json"))
                .andExpect(jsonPath("$.type").value("Person"))
                .andExpect(jsonPath("$.isCat").value(false));
        events.clear();

        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("bot", "true")
                        .param("is_cat", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bot").value(true))
                .andExpect(jsonPath("$.is_cat").value(true));
        assertThat(events.stream(ProfileUpdated.class)).hasSize(1);
        mockMvc.perform(get("/users/alice").accept("application/activity+json"))
                .andExpect(jsonPath("$.type").value("Service"))
                .andExpect(jsonPath("$.isCat").value(true))
                .andExpect(jsonPath("$['@context'][2].isCat").value("misskey:isCat"))
                .andExpect(jsonPath("$['@context'][2].misskey").value("https://misskey-hub.net/ns#"));
        mockMvc.perform(get("/users/alice").accept(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<span class=\"profile-label\">Bot</span>")))
                .andExpect(content().string(containsString("<span class=\"profile-label\">Cat</span>")));

        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bot\":false,\"is_cat\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bot").value(false))
                .andExpect(jsonPath("$.is_cat").value(false));
        assertThat(profiles.find(alice.id()).bot()).isFalse();
        assertThat(profiles.find(alice.id()).cat()).isFalse();
        mockMvc.perform(get("/users/alice").accept(MediaType.TEXT_HTML))
                .andExpect(content().string(not(containsString("profile-label"))));
    }

    @Test
    void theSettingsPageTurnsEachSwitchOnAndOff() throws Exception {
        var session = TestOAuth.logIn(mockMvc, "alice", PASSWORD);
        Map<String, Function<Profile, Boolean>> switches = Map.of(
                "bot", Profile::bot,
                "cat", Profile::cat,
                "indexable", Profile::indexable,
                "discoverable", Profile::discoverable);

        for (var entry : switches.entrySet()) {
            mockMvc.perform(get("/settings").session(session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("name=\"" + entry.getKey() + "\"")));

            mockMvc.perform(post("/settings").session(session).with(csrf()).param(entry.getKey(), "true"))
                    .andExpect(redirectedUrl("/settings?saved"));
            assertThat(entry.getValue().apply(profiles.find(account("alice").id()))).as(entry.getKey()).isTrue();

            mockMvc.perform(post("/settings").session(session).with(csrf()))
                    .andExpect(redirectedUrl("/settings?saved"));
            assertThat(entry.getValue().apply(profiles.find(account("alice").id()))).as(entry.getKey()).isFalse();
        }
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
    void setsAndRemovesABackgroundThroughPleromasApi() throws Exception {
        mockMvc.perform(withToken(get("/api/v1/accounts/verify_credentials")))
                .andExpect(jsonPath("$.pleroma.background_image").isEmpty());

        var first = uploadBackground(TestImages.png(300, 40));
        assertThat(first).startsWith("https://profiles.example/media/").endsWith(".webp");
        mockMvc.perform(withToken(get("/api/v1/accounts/verify_credentials")))
                .andExpect(jsonPath("$.pleroma.background_image").value(first));
        mockMvc.perform(get("/api/v1/accounts/" + account("alice").id()))
                .andExpect(jsonPath("$.pleroma.background_image").value(first));

        // An empty file input keeps it, and an empty value instead of a file removes it, as on Pleroma.
        mockMvc.perform(withToken(multipart(HttpMethod.PATCH, UPDATE)
                        .file(new MockMultipartFile("pleroma_background_image", "", "application/octet-stream", new byte[0]))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pleroma.background_image").value(first));
        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("pleroma_background_image", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pleroma.background_image").isEmpty());
        mockMvc.perform(get(URI.create(first).getPath())).andExpect(status().isNotFound());

        var second = uploadBackground(TestImages.png(40, 300));
        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pleroma_background_image\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pleroma.background_image").value(second));
        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pleroma_background_image\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pleroma.background_image").isEmpty());
        mockMvc.perform(get(URI.create(second).getPath())).andExpect(status().isNotFound());

        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pleroma_background_image\":\"https://example.com/b.png\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("multipart")));
    }

    /**
     * Other servers never see it, so they aren't told about it.
     */
    @Test
    void changingOnlyTheBackgroundIsntAProfileUpdate() throws Exception {
        var alice = account("alice");
        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("display_name", "Before"))
                .andExpect(status().isOk());
        var updatedAt = profiles.find(alice.id()).updatedAt();
        events.clear();

        uploadBackground(TestImages.png(30, 30));
        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("display_name", "Before")
                        .param("pleroma_background_image", ""))
                .andExpect(status().isOk());

        assertThat(events.stream(ProfileUpdated.class)).isEmpty();
        assertThat(profiles.find(alice.id()).updatedAt()).isEqualTo(updatedAt);
        assertThat(profiles.find(alice.id()).background()).isNull();
    }

    @Test
    void removingAProfileRemovesItsBackground() throws Exception {
        var erin = account("erin");
        profiles.update(erin.id(), ProfileChange.NONE.withBackground(ProfileChange.Image.upload(TestImages.png(20, 20))));
        var path = profiles.find(erin.id()).background().path();
        mockMvc.perform(get(path)).andExpect(status().isOk());

        profiles.removeAll(erin.id());

        mockMvc.perform(get(path)).andExpect(status().isNotFound());
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
    void refusesAnImageOrABackgroundOverTheLimit() throws Exception {
        mockMvc.perform(withToken(multipart(HttpMethod.PATCH, UPDATE)
                        .file(new MockMultipartFile("header", "h.png", "image/png", new byte[300 * 1024]))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("larger than")));
        mockMvc.perform(withToken(multipart(HttpMethod.PATCH, UPDATE)
                        .file(new MockMultipartFile("pleroma_background_image", "b.png", "image/png", new byte[300 * 1024]))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("Background")));
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
                true, false, false, true, false, false, Instant.parse("2020-03-04T05:06:07Z"), URI.create("https://" + host + "/@bob"));
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
                .andExpect(jsonPath("$.emojis[0].shortcode").value("blob"))
                .andExpect(jsonPath("$.pleroma.background_image").isEmpty());
    }

    @Test
    void anOldCopyOfAnAccountOnAnotherServersAvatarIsRemovedAndDownloadedAgainLater() throws Exception {
        var host = "r" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/carol");
        var avatar = URI.create("https://media." + host + "/avatar.png");
        var header = URI.create("https://media." + host + "/header.png");
        var profile = new RemoteProfile("Carol", "", "", avatar, header, List.of(), false, false, false, false, false, false, null,
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
        return new RemoteProfile("Dave", "", "", avatar, null, List.of(), false, false, false, false, false, false, null, null);
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
        profiles.update(carol.id(), ProfileChange.NONE.withDisplayName("Carol").withNote("Bio").withIndexable(true)
                .withDiscoverable(true).withBot(true).withCat(true));
        actors.suspend(carol.id());
        try {
            mockMvc.perform(get("/users/carol").accept("application/activity+json"))
                    .andExpect(jsonPath("$.suspended").value(true))
                    .andExpect(jsonPath("$.type").value("Person"))
                    .andExpect(jsonPath("$.name").doesNotExist())
                    .andExpect(jsonPath("$.summary").doesNotExist())
                    .andExpect(jsonPath("$.indexable").doesNotExist())
                    .andExpect(jsonPath("$.discoverable").doesNotExist())
                    .andExpect(jsonPath("$.isCat").doesNotExist());
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
    void setsTheBackgroundFromTheSettingsPageAndShowsItOnTheProfilePage() throws Exception {
        var session = TestOAuth.logIn(mockMvc, "alice", PASSWORD);
        mockMvc.perform(get("/settings").session(session))
                .andExpect(content().string(containsString("name=\"background\"")));

        mockMvc.perform(multipart("/settings")
                        .file(new MockMultipartFile("background", "b.png", "image/png", TestImages.png(40, 20)))
                        .session(session).with(csrf()))
                .andExpect(redirectedUrl("/settings?saved"));
        var background = profiles.find(account("alice").id()).background();
        assertThat(background).isNotNull();
        mockMvc.perform(get("/settings").session(session))
                .andExpect(content().string(containsString("name=\"removeBackground\"")));
        mockMvc.perform(get("/users/alice").accept(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("background-image: url(&#39;https://profiles.example"
                        + background.path() + "&#39;)")));

        mockMvc.perform(post("/settings").session(session).with(csrf()).param("removeBackground", "true"))
                .andExpect(redirectedUrl("/settings?saved"));
        assertThat(profiles.find(account("alice").id()).background()).isNull();
        mockMvc.perform(get("/users/alice").accept(MediaType.TEXT_HTML))
                .andExpect(content().string(not(containsString("background-image"))));
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

    private String uploadBackground(byte[] image) throws Exception {
        var body = mockMvc.perform(withToken(multipart(HttpMethod.PATCH, UPDATE)
                        .file(new MockMultipartFile("pleroma_background_image", "b.png", "image/png", image))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.pleroma.background_image");
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
