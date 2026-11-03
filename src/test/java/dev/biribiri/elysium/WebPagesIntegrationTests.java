package dev.biribiri.elysium;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusPage;
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

import java.util.UUID;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://elysium.example",
        "elysium.registration.enabled=true"
})
@AutoConfigureMockMvc
class WebPagesIntegrationTests {

    private static final String BROWSER_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ActorService actors;

    @Autowired
    private StatusService statuses;

    private Actor alice;
    private Actor bob;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM status").update();
        alice = account("alice");
        bob = account("bob");
    }

    // Choosing between the page and the actor document

    @Test
    void servesTheProfilePageToClientsThatAcceptHtml() throws Exception {
        for (var accept : new String[]{BROWSER_ACCEPT, "text/html", "text/*", "*/*", "application/json, */*;q=0.1"}) {
            expectProfilePage(get("/users/alice").header("Accept", accept));
        }
        expectProfilePage(get("/users/alice"));
    }

    @Test
    void rejectsClientsThatCannotReadThePages() throws Exception {
        var status = post(alice, "public text", Visibility.PUBLIC);
        for (var path : new String[]{"/users/alice", path(alice, status)}) {
            for (var accept : new String[]{"application/json", "image/png", "nonsense"}) {
                mockMvc.perform(get(path).header("Accept", accept)).andExpect(status().isNotAcceptable());
            }
        }
        // Statuses have no ActivityPub document yet.
        mockMvc.perform(get(path(alice, status)).header("Accept", "application/activity+json"))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    void servesTheActorDocumentToActivityPubClients() throws Exception {
        for (var accept : new String[]{
                "application/activity+json",
                "application/ld+json; profile=\"https://www.w3.org/ns/activitystreams\"",
                "application/ld+json; profile=\"http://www.w3.org/ns/activitystreams\"",
                "application/ld+json; profile=\"https://www.w3.org/ns/activitystreams https://example.com/other\"",
                "application/ld+json",
                "application/activity+json, */*;q=0.1"}) {
            mockMvc.perform(get("/users/alice").header("Accept", accept))
                    .andExpect(status().isOk())
                    .andExpect(header().stringValues("Vary", hasItem("Accept")))
                    .andExpect(jsonPath("$.preferredUsername").value("alice"));
        }
    }

    @Test
    void allowsCrossOriginRequestsForBothRepresentations() throws Exception {
        mockMvc.perform(options("/users/alice")
                        .header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));

        for (var accept : new String[]{"application/activity+json", "*/*"}) {
            mockMvc.perform(get("/users/alice").header("Accept", accept).header("Origin", "https://client.example"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        }
    }

    @Test
    void returnsNotFoundForUnknownProfiles() throws Exception {
        for (var accept : new String[]{"text/html", "application/activity+json"}) {
            mockMvc.perform(get("/users/nobody").header("Accept", accept))
                    .andExpect(status().isNotFound())
                    .andExpect(header().stringValues("Vary", hasItem("Accept")));
        }
        mockMvc.perform(get("/users/not-valid")).andExpect(status().isNotFound());
    }

    // Front page

    @Test
    void frontPageShowsOnlyPublicStatuses() throws Exception {
        post(alice, "public text", Visibility.PUBLIC);
        post(alice, "unlisted text", Visibility.UNLISTED);
        post(alice, "private text", Visibility.PRIVATE);
        post(alice, "direct text", Visibility.DIRECT);

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("elysium.example")))
                .andExpect(content().string(containsString("href=\"/users/alice\"")))
                .andExpect(content().string(containsString("public text")))
                .andExpect(content().string(not(containsString("unlisted text"))))
                .andExpect(content().string(not(containsString("private text"))))
                .andExpect(content().string(not(containsString("direct text"))));
    }

    @Test
    void frontPageOffersLoggingInAndSigningUp() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(content().string(containsString("href=\"/login\"")))
                .andExpect(content().string(containsString("href=\"/signup\"")));
    }

    @Test
    void frontPageDoesNotOfferLoggingInToALoggedInUser() throws Exception {
        mockMvc.perform(get("/").with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("href=\"/login\""))));
    }

    @Test
    void frontPageWorksWithNoStatuses() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No posts yet.")));
    }

    // Profile

    @Test
    void profileShowsPublicAndUnlistedStatusesOnly() throws Exception {
        post(alice, "public text", Visibility.PUBLIC);
        post(alice, "unlisted text", Visibility.UNLISTED);
        post(alice, "private text", Visibility.PRIVATE);
        post(alice, "direct text", Visibility.DIRECT);
        post(bob, "text from bob", Visibility.PUBLIC);

        mockMvc.perform(get("/users/alice"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("@alice@elysium.example")))
                .andExpect(content().string(containsString("public text")))
                .andExpect(content().string(containsString("unlisted text")))
                .andExpect(content().string(not(containsString("private text"))))
                .andExpect(content().string(not(containsString("direct text"))))
                .andExpect(content().string(not(containsString("text from bob"))));
    }

    @Test
    void profileLeavesOutRepliesToOtherAccounts() throws Exception {
        var parent = post(bob, "text from bob", Visibility.PUBLIC);
        reply(alice, "reply to bob", parent);

        mockMvc.perform(get("/users/alice"))
                .andExpect(content().string(not(containsString("reply to bob"))));
    }

    @Test
    void profilePagesThroughOlderStatuses() throws Exception {
        for (var i = 0; i < StatusPage.DEFAULT_LIMIT + 1; i++) {
            post(alice, "post number " + i + ".", Visibility.PUBLIC);
        }
        var oldest = "post number 0.";

        var firstPage = mockMvc.perform(get("/users/alice"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(oldest))))
                .andExpect(content().string(containsString("max_id=")))
                .andReturn().getResponse().getContentAsString();

        var cursor = firstPage.substring(firstPage.indexOf("max_id=") + "max_id=".length()).split("\"")[0];
        mockMvc.perform(get("/users/alice").param("max_id", cursor))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(oldest)))
                .andExpect(content().string(not(containsString("max_id="))));
    }

    @Test
    void profileReturnsNotFoundForAMalformedCursor() throws Exception {
        for (var cursor : new String[]{"nonsense", "", "1-1-1-1-1"}) {
            mockMvc.perform(get("/users/alice").param("max_id", cursor))
                    .andExpect(status().isNotFound());
        }
    }

    // Status

    @Test
    void showsPublicAndUnlistedStatuses() throws Exception {
        for (var visibility : new Visibility[]{Visibility.PUBLIC, Visibility.UNLISTED}) {
            var status = post(alice, "visible text", visibility);
            mockMvc.perform(get(path(alice, status)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                    .andExpect(content().string(containsString("visible text")))
                    .andExpect(content().string(containsString(
                            "datetime=\"" + status.publishedAt() + "\"")));
        }
    }

    @Test
    void hidesPrivateAndDirectStatuses() throws Exception {
        for (var visibility : new Visibility[]{Visibility.PRIVATE, Visibility.DIRECT}) {
            var status = post(alice, "hidden text", visibility);
            mockMvc.perform(get(path(alice, status))).andExpect(status().isNotFound());
        }
    }

    @Test
    void aLoginSessionDoesNotRevealPrivateStatuses() throws Exception {
        var status = post(alice, "hidden text", Visibility.PRIVATE);

        mockMvc.perform(get(path(alice, status)).with(user("alice")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/users/alice").with(user("alice")))
                .andExpect(content().string(not(containsString("hidden text"))));
    }

    @Test
    void statusIsOnlyShownUnderItsAuthor() throws Exception {
        var status = post(alice, "text from alice", Visibility.PUBLIC);

        mockMvc.perform(get(path(bob, status))).andExpect(status().isNotFound());
        mockMvc.perform(get("/users/ALICE/statuses/" + status.id()))
                .andExpect(status().isOk());
    }

    @Test
    void returnsNotFoundForUnknownAndMalformedStatusIds() throws Exception {
        for (var id : new String[]{UUID.randomUUID().toString(), "nonsense", "1-1-1-1-1"}) {
            mockMvc.perform(get("/users/alice/statuses/" + id))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void linksToAVisibleParent() throws Exception {
        var parent = post(bob, "text from bob", Visibility.PUBLIC);
        var reply = reply(alice, "reply to bob", parent);

        mockMvc.perform(get(path(alice, reply)))
                .andExpect(content().string(containsString("In reply to")))
                .andExpect(content().string(containsString("href=\"" + path(bob, parent) + "\"")));
    }

    @Test
    void doesNotLinkToAHiddenParent() throws Exception {
        var parent = post(alice, "hidden text", Visibility.PRIVATE);
        var reply = reply(alice, "reply to myself", parent);

        mockMvc.perform(get(path(alice, reply)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("In reply to"))))
                .andExpect(content().string(not(containsString(parent.id().toString()))));
    }

    // Rendering

    @Test
    void escapesStatusTextAndContentWarnings() throws Exception {
        var status = statuses.create(alice.id(), new NewStatus(
                "<script>alert('text')</script>", "<script>alert('warning')</script>", null, Visibility.PUBLIC, false, null));

        mockMvc.perform(get(path(alice, status)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<script>"))))
                .andExpect(content().string(containsString("&lt;script&gt;")));
    }

    @Test
    void keepsLinksInStatusText() throws Exception {
        var status = post(alice, "See https://example.com/page", Visibility.PUBLIC);

        mockMvc.perform(get(path(alice, status)))
                .andExpect(content().string(containsString("<a href=\"https://example.com/page\"")));
    }

    @Test
    void foldsSensitiveStatuses() throws Exception {
        var warned = statuses.create(alice.id(), new NewStatus(
                "behind a warning", "Spoilers", null, Visibility.PUBLIC, false, null));
        var sensitive = statuses.create(alice.id(), new NewStatus(
                "marked sensitive", null, null, Visibility.PUBLIC, true, null));
        var plain = post(alice, "nothing to hide", Visibility.PUBLIC);

        mockMvc.perform(get(path(alice, warned)))
                .andExpect(content().string(allOf(
                        containsString("<details>"), containsString("<summary>Spoilers</summary>"),
                        containsString("behind a warning"))));
        mockMvc.perform(get(path(alice, sensitive)))
                .andExpect(content().string(containsString("<summary>Sensitive content</summary>")));
        mockMvc.perform(get(path(alice, plain)))
                .andExpect(content().string(not(containsString("<details>"))));
    }

    @Test
    void marksTheLanguageOfAStatus() throws Exception {
        var status = statuses.create(alice.id(), new NewStatus(
                "bonjour", null, "fr", Visibility.PUBLIC, false, null));

        mockMvc.perform(get(path(alice, status)))
                .andExpect(content().string(containsString("lang=\"fr\"")));
    }

    @Test
    void doesNotAssumeTheLanguageOfAStatusWithoutOne() throws Exception {
        var status = post(alice, "no language set", Visibility.PUBLIC);

        mockMvc.perform(get(path(alice, status)))
                .andExpect(content().string(containsString("lang=\"und\"")));
    }

    @Test
    void profileDoesNotCountPostsItCannotShow() throws Exception {
        post(alice, "private text", Visibility.PRIVATE);

        mockMvc.perform(get("/users/alice"))
                .andExpect(content().string(containsString("No posts to show.")))
                .andExpect(content().string(not(containsString("Posts:"))));
    }

    private void expectProfilePage(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().stringValues("Vary", hasItem("Accept")))
                .andExpect(content().string(containsString("@alice@elysium.example")));
    }

    private Actor account(String username) {
        actors.createLocalIfAbsent(username);
        return actors.findLocalByUsername(username).orElseThrow();
    }

    private Status post(Actor author, String text, Visibility visibility) {
        return statuses.create(author.id(), new NewStatus(text, null, null, visibility, false, null));
    }

    private Status reply(Actor author, String text, Status parent) {
        return statuses.create(author.id(), new NewStatus(text, null, null, Visibility.PUBLIC, false, parent.id()));
    }

    private static String path(Actor author, Status status) {
        return "/users/" + author.username() + "/statuses/" + status.id();
    }
}
