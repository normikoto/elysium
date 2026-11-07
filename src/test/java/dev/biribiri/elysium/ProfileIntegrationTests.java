package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.ProfileChange;
import dev.biribiri.elysium.actor.ProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
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
        "elysium.registration.enabled=true",
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
    private ActorService actors;
    @Autowired
    private ProfileService profiles;

    @BeforeEach
    void setUp() throws Exception {
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
                        .param("locked", "true"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("locked")));
        mockMvc.perform(withToken(patch(UPDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":{\"privacy\":\"private\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("source[privacy]")));
        assertThat(profiles.find(account("alice").id()).displayName()).isEqualTo("Alice");
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
                        .param("username", username)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(status().is3xxRedirection());
        var token = TestOAuth.userToken(mockMvc, username, PASSWORD, "read write");
        TOKENS.put(username, token);
        return token;
    }
}
