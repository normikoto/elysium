package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.RemoteEdit;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.instance.base-url=https://tags.example")
@AutoConfigureMockMvc
class HashtagsIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String TAG = "/api/v1/timelines/tag/";
    private static final MediaType ACTIVITY_JSON = MediaType.parseMediaType("application/activity+json");

    /**
     * Logging in is slow because of Argon2, so each token is only created once.
     */
    private static final Map<String, String> TOKENS = new HashMap<>();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;
    @Autowired
    private StatusService statuses;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM status").update();
        TestAccounts.makeSureAnAccountExists(actors);
    }

    // Statuses

    @Test
    void linksAndListsAStatusesHashtags() throws Exception {
        mockMvc.perform(createRequest(writer(), "Hello #Cats and #café, #cats again"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("<p>Hello <a href=\"https://tags.example/tags/cats\" "
                        + "class=\"mention hashtag\" rel=\"tag\">#<span>Cats</span></a> and "
                        + "<a href=\"https://tags.example/tags/caf%C3%A9\" class=\"mention hashtag\" rel=\"tag\">"
                        + "#<span>café</span></a>, <a href=\"https://tags.example/tags/cats\" class=\"mention hashtag\" "
                        + "rel=\"tag\">#<span>cats</span></a> again</p>"))
                .andExpect(jsonPath("$.tags", hasSize(2)))
                .andExpect(jsonPath("$.tags[?(@.name == 'cats')].url").value("https://tags.example/tags/cats"))
                .andExpect(jsonPath("$.tags[?(@.name == 'café')].url").value("https://tags.example/tags/caf%C3%A9"));
    }

    @Test
    void anEditReplacesTheHashtags() throws Exception {
        var id = create(writer(), "#old #kept", "public");

        mockMvc.perform(withToken(put("/api/v1/statuses/" + id), writer())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", "#kept #new"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags[*].name").value(org.hamcrest.Matchers.containsInAnyOrder("kept", "new")));
        expectIds(get(TAG + "old"));
        expectIds(get(TAG + "new"), id);
    }

    // Timeline

    @Test
    void listsPublicStatusesWithTheTagNewestFirst() throws Exception {
        var first = create(writer(), "#cats one", "public");
        var reply = expectCreated(createRequest(writer(), "#cats reply").param("in_reply_to_id", first));
        create(writer(), "#cats unlisted", "unlisted");
        create(writer(), "#cats private", "private");
        create(writer(), "#dogs only", "public");
        var remote = remote("cats");

        // Tags match without regard to case, and replies are listed.
        expectIds(get(TAG + "CATS"), remote, reply, first);
        expectIds(get(TAG + "cats").param("local", "true"), reply, first);
        expectIds(get(TAG + "cats").param("remote", "true"), remote);
        expectIds(get(TAG + "cats").param("only_media", "true"));
        expectIds(withToken(get(TAG + "cats"), reader()), remote, reply, first);
    }

    @Test
    void filtersByAnyAllAndNone() throws Exception {
        var cats = create(writer(), "#cats", "public");
        var dogs = create(writer(), "#dogs", "public");
        var both = create(writer(), "#cats #dogs", "public");
        var birds = create(writer(), "#cats #birds", "public");

        expectIds(get(TAG + "cats").param("any[]", "dogs"), birds, both, dogs, cats);
        expectIds(get(TAG + "cats").param("all[]", "dogs"), both);
        expectIds(get(TAG + "cats").param("none[]", "dogs", "birds"), cats);
        expectIds(get(TAG + "cats").param("all[]", "not a tag"));
        expectIds(get(TAG + "cats").param("none[]", "not a tag"), birds, both, cats);
    }

    @Test
    void anInvalidNameListsNothing() throws Exception {
        create(writer(), "#cats", "public");
        mockMvc.perform(get(TAG + "123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()))
                .andExpect(header().doesNotExist("Link"));
    }

    @Test
    void linksToTheNeighbouringPages() throws Exception {
        var older = create(writer(), "#café", "public");
        var newer = create(writer(), "#café", "public");

        mockMvc.perform(get(TAG + "café").param("limit", "1").param("all[]", "Café").param("local", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Link",
                        "<https://tags.example/api/v1/timelines/tag/caf%C3%A9?limit=1&all%5B%5D=Caf%C3%A9&local=true"
                                + "&max_id=%s>; rel=\"next\", ".formatted(newer)
                                + "<https://tags.example/api/v1/timelines/tag/caf%C3%A9?limit=1&all%5B%5D=Caf%C3%A9"
                                + "&local=true&min_id=%s>; rel=\"prev\"".formatted(newer)));
        expectIds(get(TAG + "café").param("max_id", newer), older);
    }

    @Test
    void leavesOutStatusesFromSuspendedAccounts() throws Exception {
        var remote = remote("cats");
        jdbc.sql("UPDATE actor SET suspended_at = now() WHERE id = (SELECT actor_id FROM status WHERE id = :id)")
                .param("id", UUID.fromString(remote))
                .update();
        expectIds(get(TAG + "cats"));
        mockMvc.perform(get("/api/v2/search").param("q", "cat").param("type", "hashtags"))
                .andExpect(jsonPath("$.hashtags[*].name").value(org.hamcrest.Matchers.contains("cat")));
    }

    @Test
    void aRemoteEditReplacesTheHashtags() throws Exception {
        var author = remoteAuthor();
        var uri = URI.create(author.uri() + "/notes/" + UUID.randomUUID());
        statuses.storeRemote(new RemoteStatus(uri, null, author.id(), "<p>Hi</p>", null, null, Visibility.PUBLIC, false,
                null, Instant.parse("2026-01-01T00:00:00Z"), null, Map.of(), Set.of("Old", "kept", "not valid")));
        var id = jdbc.sql("SELECT id FROM status WHERE uri = :uri").param("uri", uri.toString())
                .query(String.class).single();
        expectIds(get(TAG + "old"), id);

        statuses.updateRemote(new RemoteEdit(uri, author.id(), "<p>Edited</p>", null, null, false,
                Instant.parse("2026-02-01T00:00:00Z"), Set.of("kept", "new")));

        expectIds(get(TAG + "old"));
        expectIds(get(TAG + "new"), id);
        expectIds(get(TAG + "kept"), id);
    }

    // Tags and search

    @Test
    void showsATagWithItsHistory() throws Exception {
        create(writer(), "#cats", "public");
        create(writer(), "#Cats again", "public");
        create(writer(), "#cats hidden", "private");

        mockMvc.perform(get("/api/v1/tags/Cats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("cats"))
                .andExpect(jsonPath("$.url").value("https://tags.example/tags/cats"))
                .andExpect(jsonPath("$.history", hasSize(7)))
                .andExpect(jsonPath("$.history[0].uses").value("2"))
                .andExpect(jsonPath("$.history[0].accounts").value("1"))
                .andExpect(jsonPath("$.history[1].uses").value("0"))
                .andExpect(jsonPath("$.following").doesNotExist());
        mockMvc.perform(withToken(get("/api/v1/tags/unused"), reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history[0].uses").value("0"))
                .andExpect(jsonPath("$.following").value(false));
        mockMvc.perform(get("/api/v1/tags/123")).andExpect(status().isNotFound());
    }

    @Test
    void searchFindsTagsByTheStartOfTheirName() throws Exception {
        create(writer(), "#catsoftheworld #catsoftheworld2", "public");
        create(writer(), "#catsoftheworld", "public");
        create(writer(), "#catsprivate", "private");
        create(writer(), "#dogs", "public");

        mockMvc.perform(get("/api/v2/search").param("q", "#catsof"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts", empty()))
                .andExpect(jsonPath("$.hashtags[*].name").value(org.hamcrest.Matchers.contains(
                        "catsof", "catsoftheworld", "catsoftheworld2")))
                .andExpect(jsonPath("$.hashtags[1].history[0].uses").value("2"));
        mockMvc.perform(get("/api/v2/search").param("q", "catsoftheworld").param("type", "hashtags")
                        .param("limit", "1"))
                .andExpect(jsonPath("$.hashtags[*].name").value(org.hamcrest.Matchers.contains("catsoftheworld")));
        mockMvc.perform(get("/api/v2/search").param("q", "catsof").param("type", "hashtags").param("offset", "1"))
                .andExpect(jsonPath("$.hashtags[*].name").value(org.hamcrest.Matchers.contains("catsoftheworld2")));
        mockMvc.perform(get("/api/v2/search").param("q", "catsof").param("type", "accounts"))
                .andExpect(jsonPath("$.hashtags", empty()));
    }

    // Profiles

    @Test
    void linksAndFederatesTheHashtagsInABio() throws Exception {
        mockMvc.perform(withToken(patch("/api/v1/accounts/update_credentials"), writer())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("note", "I like #Cats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("<p>I like <a href=\"https://tags.example/tags/cats\" "
                        + "class=\"mention hashtag\" rel=\"tag\">#<span>Cats</span></a></p>"));

        mockMvc.perform(get("/users/tagwriter").accept(ACTIVITY_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tag", hasSize(1)))
                .andExpect(jsonPath("$.tag[0].type").value("Hashtag"))
                .andExpect(jsonPath("$.tag[0].name").value("#cats"))
                .andExpect(jsonPath("$.tag[0].href").value("https://tags.example/tags/cats"))
                .andExpect(jsonPath("$['@context'][2].Hashtag").value("as:Hashtag"));
        mockMvc.perform(get("/users/tagwriter").accept(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("href=\"https://tags.example/tags/cats\"")));

        mockMvc.perform(withToken(patch("/api/v1/accounts/update_credentials"), writer())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("note", ""))
                .andExpect(status().isOk());
        mockMvc.perform(get("/users/tagwriter").accept(ACTIVITY_JSON))
                .andExpect(jsonPath("$.tag").doesNotExist());
    }

    // Federation and pages

    @Test
    void aNoteNamesItsHashtagsAfterItsMentions() throws Exception {
        reader();
        var id = create(writer(), "@tagreader #Cats", "public");

        mockMvc.perform(get("/users/tagwriter/statuses/" + id).accept(ACTIVITY_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tag", hasSize(2)))
                .andExpect(jsonPath("$.tag[0].type").value("Mention"))
                .andExpect(jsonPath("$.tag[1].type").value("Hashtag"))
                .andExpect(jsonPath("$.tag[1].name").value("#cats"))
                .andExpect(jsonPath("$.tag[1].href").value("https://tags.example/tags/cats"))
                .andExpect(jsonPath("$['@context'][1].Hashtag").value("as:Hashtag"));
    }

    @Test
    void showsAPageOfTheTagsPublicStatuses() throws Exception {
        create(writer(), "Shown #cats", "public");
        create(writer(), "Hidden #cats", "private");
        remote("cats");

        mockMvc.perform(get("/tags/Cats").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("#cats - Elysium")))
                .andExpect(content().string(containsString("Shown")))
                .andExpect(content().string(containsString("From afar")))
                .andExpect(content().string(not(containsString("Hidden"))));
        mockMvc.perform(get("/tags/123").accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound());
        mockMvc.perform(get("/tags/cats").accept(MediaType.APPLICATION_JSON)).andExpect(status().isNotAcceptable());
    }

    // Helpers

    private String writer() throws Exception {
        return token("tagwriter");
    }

    private String reader() throws Exception {
        return token("tagreader");
    }

    private dev.biribiri.elysium.actor.Actor remoteAuthor() {
        var host = "h" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/s");
        return actors.upsertRemote(
                new RemoteActorProfile(uri, "s", host, URI.create(uri + "/inbox"), null, null, null, null));
    }

    /**
     * Stores a public status from a new actor on another server, with one tag.
     */
    private String remote(String tag) {
        var author = remoteAuthor();
        return statuses.storeRemote(new RemoteStatus(
                URI.create(author.uri() + "/notes/" + UUID.randomUUID()), null, author.id(), "<p>From afar</p>", null,
                null, Visibility.PUBLIC, false, null, Instant.now(), null, Map.of(), Set.of(tag)))
                .orElseThrow().toString();
    }

    private String create(String token, String text, String visibility) throws Exception {
        return expectCreated(createRequest(token, text).param("visibility", visibility));
    }

    private String expectCreated(MockHttpServletRequestBuilder request) throws Exception {
        var body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private MockHttpServletRequestBuilder createRequest(String token, String text) {
        return withToken(post("/api/v1/statuses"), token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("status", text);
    }

    private void expectIds(MockHttpServletRequestBuilder request, String... ids) throws Exception {
        var body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> found = JsonPath.read(body, "$[*].id");
        assertThat(found).containsExactly(ids);
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
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
