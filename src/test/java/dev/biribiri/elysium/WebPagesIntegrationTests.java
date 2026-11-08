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

import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://elysium.example",
        "elysium.registration.mode=open"
})
@AutoConfigureMockMvc
class WebPagesIntegrationTests {

    private static final String BROWSER_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";
    private static final MediaType ACTIVITY_JSON = MediaType.parseMediaType("application/activity+json");
    private static final String PUBLIC_COLLECTION = "https://www.w3.org/ns/activitystreams#Public";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ActorService actors;

    @Autowired
    private StatusService statuses;

    private Actor wanda;
    private Actor walt;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM status").update();
        wanda = account("wanda");
        walt = account("walt");
    }

    // Choosing between the page and the actor document

    @Test
    void servesTheProfilePageToClientsThatAcceptHtml() throws Exception {
        for (var accept : new String[]{BROWSER_ACCEPT, "text/html", "text/*", "*/*", "application/json, */*;q=0.1"}) {
            expectProfilePage(get("/users/wanda").header("Accept", accept));
        }
        expectProfilePage(get("/users/wanda"));
    }

    @Test
    void rejectsClientsThatCannotReadThePages() throws Exception {
        var status = post(wanda, "public text", Visibility.PUBLIC);
        for (var path : new String[]{"/users/wanda", path(wanda, status)}) {
            for (var accept : new String[]{"application/json", "image/png", "nonsense"}) {
                mockMvc.perform(get(path).header("Accept", accept)).andExpect(status().isNotAcceptable());
            }
        }
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
            mockMvc.perform(get("/users/wanda").header("Accept", accept))
                    .andExpect(status().isOk())
                    .andExpect(header().stringValues("Vary", hasItem("Accept")))
                    .andExpect(jsonPath("$.preferredUsername").value("wanda"));
        }
    }

    @Test
    void allowsCrossOriginRequestsForBothRepresentations() throws Exception {
        mockMvc.perform(options("/users/wanda")
                        .header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));

        for (var accept : new String[]{"application/activity+json", "*/*"}) {
            mockMvc.perform(get("/users/wanda").header("Accept", accept).header("Origin", "https://client.example"))
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
        post(wanda, "public text", Visibility.PUBLIC);
        post(wanda, "unlisted text", Visibility.UNLISTED);
        post(wanda, "private text", Visibility.PRIVATE);
        post(wanda, "direct text", Visibility.DIRECT);

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("elysium.example")))
                .andExpect(content().string(containsString("href=\"/users/wanda\"")))
                .andExpect(content().string(containsString("public text")))
                .andExpect(content().string(not(containsString("unlisted text"))))
                .andExpect(content().string(not(containsString("private text"))))
                .andExpect(content().string(not(containsString("direct text"))));
    }

    @Test
    void frontPageOffersLoggingInAndSigningUpWithNoStatuses() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/login\"")))
                .andExpect(content().string(containsString("href=\"/signup\"")))
                .andExpect(content().string(containsString("No posts yet.")));
    }

    @Test
    void frontPageDoesNotOfferLoggingInToALoggedInUser() throws Exception {
        mockMvc.perform(get("/").with(user("wanda")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("href=\"/login\""))));
    }

    // Profile

    @Test
    void profileShowsPublicAndUnlistedStatusesOnly() throws Exception {
        post(wanda, "public text", Visibility.PUBLIC);
        post(wanda, "unlisted text", Visibility.UNLISTED);
        post(wanda, "private text", Visibility.PRIVATE);
        post(wanda, "direct text", Visibility.DIRECT);
        post(walt, "text from walt", Visibility.PUBLIC);

        mockMvc.perform(get("/users/wanda"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("@wanda@elysium.example")))
                .andExpect(content().string(containsString("public text")))
                .andExpect(content().string(containsString("unlisted text")))
                .andExpect(content().string(not(containsString("private text"))))
                .andExpect(content().string(not(containsString("direct text"))))
                .andExpect(content().string(not(containsString("text from walt"))));
    }

    @Test
    void profileLeavesOutRepliesToOtherAccounts() throws Exception {
        var parent = post(walt, "text from walt", Visibility.PUBLIC);
        reply(wanda, "reply to walt", parent);

        mockMvc.perform(get("/users/wanda"))
                .andExpect(content().string(not(containsString("reply to walt"))));
    }

    @Test
    void profilePagesThroughOlderStatuses() throws Exception {
        for (var i = 0; i < StatusPage.DEFAULT_LIMIT + 1; i++) {
            post(wanda, "post number " + i + ".", Visibility.PUBLIC);
        }
        var oldest = "post number 0.";

        var firstPage = mockMvc.perform(get("/users/wanda"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(oldest))))
                .andExpect(content().string(containsString("max_id=")))
                .andReturn().getResponse().getContentAsString();

        var cursor = firstPage.substring(firstPage.indexOf("max_id=") + "max_id=".length()).split("\"")[0];
        mockMvc.perform(get("/users/wanda").param("max_id", cursor))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(oldest)))
                .andExpect(content().string(not(containsString("max_id="))));
    }

    @Test
    void profileReturnsNotFoundForAMalformedCursor() throws Exception {
        for (var cursor : new String[]{"nonsense", "", "1-1-1-1-1"}) {
            mockMvc.perform(get("/users/wanda").param("max_id", cursor))
                    .andExpect(status().isNotFound());
        }
    }

    // Status

    @Test
    void showsPublicAndUnlistedStatuses() throws Exception {
        for (var visibility : new Visibility[]{Visibility.PUBLIC, Visibility.UNLISTED}) {
            var status = post(wanda, "visible text", visibility);
            mockMvc.perform(get(path(wanda, status)))
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
            var status = post(wanda, "hidden text", visibility);
            mockMvc.perform(get(path(wanda, status))).andExpect(status().isNotFound());
        }
    }

    @Test
    void aLoginSessionDoesNotRevealPrivateStatuses() throws Exception {
        var status = post(wanda, "hidden text", Visibility.PRIVATE);

        mockMvc.perform(get(path(wanda, status)).with(user("wanda")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/users/wanda").with(user("wanda")))
                .andExpect(content().string(not(containsString("hidden text"))));
    }

    @Test
    void statusIsOnlyShownUnderItsAuthor() throws Exception {
        var status = post(wanda, "text from wanda", Visibility.PUBLIC);

        mockMvc.perform(get(path(walt, status))).andExpect(status().isNotFound());
        mockMvc.perform(get("/users/WANDA/statuses/" + status.id()))
                .andExpect(status().isOk());
    }

    @Test
    void returnsNotFoundForUnknownAndMalformedStatusIds() throws Exception {
        for (var id : new String[]{UUID.randomUUID().toString(), "nonsense", "1-1-1-1-1"}) {
            mockMvc.perform(get("/users/wanda/statuses/" + id))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void linksToAVisibleParent() throws Exception {
        var parent = post(walt, "text from walt", Visibility.PUBLIC);
        var reply = reply(wanda, "reply to walt", parent);

        mockMvc.perform(get(path(wanda, reply)))
                .andExpect(content().string(containsString("In reply to")))
                .andExpect(content().string(containsString("href=\"" + path(walt, parent) + "\"")));
    }

    @Test
    void doesNotLinkToAHiddenParent() throws Exception {
        var parent = post(wanda, "hidden text", Visibility.PRIVATE);
        var reply = reply(wanda, "reply to myself", parent);

        mockMvc.perform(get(path(wanda, reply)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("In reply to"))))
                .andExpect(content().string(not(containsString(parent.id().toString()))));
    }

    // Followers

    @Test
    void actorDocumentLinksToItsFollowers() throws Exception {
        mockMvc.perform(get("/users/wanda").accept(ACTIVITY_JSON))
                .andExpect(jsonPath("$.followers").value(wanda.uri() + "/followers"));
    }

    @Test
    void followersCollectionLinksToItsFirstPage() throws Exception {
        mockMvc.perform(get("/users/wanda/followers").accept(ACTIVITY_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(ACTIVITY_JSON))
                .andExpect(jsonPath("$.id").value(wanda.uri() + "/followers"))
                .andExpect(jsonPath("$.type").value("OrderedCollection"))
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.first").value(wanda.uri() + "/followers?page=true"))
                .andExpect(jsonPath("$.orderedItems").doesNotHaveJsonPath());
    }

    @Test
    void followersCollectionCanBeReadFromAnyOrigin() throws Exception {
        mockMvc.perform(get("/users/wanda/followers").accept(ACTIVITY_JSON).header("Origin", "https://other.example"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"));
    }

    @Test
    void followersCollectionHasNoWebPage() throws Exception {
        mockMvc.perform(get("/users/wanda/followers").accept(MediaType.ALL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("OrderedCollection"));
        mockMvc.perform(get("/users/wanda/followers").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    void followersCollectionOfAnUnknownUserIsNotFound() throws Exception {
        mockMvc.perform(get("/users/nobody/followers").accept(ACTIVITY_JSON)).andExpect(status().isNotFound());
    }

    // Status objects

    @Test
    void servesAPublicStatusAsANote() throws Exception {
        var status = post(wanda, "See https://example.com/page", Visibility.PUBLIC);

        mockMvc.perform(get(path(wanda, status)).accept(ACTIVITY_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(ACTIVITY_JSON))
                .andExpect(jsonPath("$['@context'][0]").value("https://www.w3.org/ns/activitystreams"))
                .andExpect(jsonPath("$['@context'][1].sensitive").value("as:sensitive"))
                .andExpect(jsonPath("$.id").value(status.uri().toString()))
                .andExpect(jsonPath("$.type").value("Note"))
                .andExpect(jsonPath("$.attributedTo").value(wanda.uri().toString()))
                .andExpect(jsonPath("$.url").value(status.uri().toString()))
                .andExpect(jsonPath("$.published")
                        .value(status.publishedAt().truncatedTo(ChronoUnit.SECONDS).toString()))
                .andExpect(jsonPath("$.to.length()").value(1))
                .andExpect(jsonPath("$.to[0]").value(PUBLIC_COLLECTION))
                .andExpect(jsonPath("$.cc.length()").value(1))
                .andExpect(jsonPath("$.cc[0]").value(wanda.uri() + "/followers"))
                .andExpect(jsonPath("$.content").value(status.content()))
                .andExpect(jsonPath("$.content").value(containsString("<a href=\"https://example.com/page\"")))
                .andExpect(jsonPath("$.sensitive").value(false))
                .andExpect(jsonPath("$.summary").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.inReplyTo").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.contentMap").doesNotHaveJsonPath());
    }

    @Test
    void addressesAnUnlistedNoteDifferently() throws Exception {
        var status = post(wanda, "unlisted text", Visibility.UNLISTED);

        mockMvc.perform(get(path(wanda, status)).accept(ACTIVITY_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.to.length()").value(1))
                .andExpect(jsonPath("$.to[0]").value(wanda.uri() + "/followers"))
                .andExpect(jsonPath("$.cc.length()").value(1))
                .andExpect(jsonPath("$.cc[0]").value(PUBLIC_COLLECTION));
    }

    @Test
    void doesNotServePrivateOrDirectStatusesAsNotes() throws Exception {
        for (var visibility : new Visibility[]{Visibility.PRIVATE, Visibility.DIRECT}) {
            var status = post(wanda, "hidden text", visibility);

            mockMvc.perform(get(path(wanda, status)).accept(ACTIVITY_JSON))
                    .andExpect(status().isNotFound())
                    .andExpect(content().string(not(containsString("hidden text"))));
        }
    }

    @Test
    void servesATombstoneForADeletedStatusThatAnyoneCouldSee() throws Exception {
        for (var visibility : new Visibility[]{Visibility.PUBLIC, Visibility.UNLISTED}) {
            var status = post(wanda, "deleted text", visibility);
            statuses.delete(status.id(), wanda.id());

            mockMvc.perform(get(path(wanda, status)).accept(ACTIVITY_JSON))
                    .andExpect(status().isGone())
                    .andExpect(content().contentTypeCompatibleWith(ACTIVITY_JSON))
                    .andExpect(jsonPath("$['@context']").value("https://www.w3.org/ns/activitystreams"))
                    .andExpect(jsonPath("$.id").value(status.uri().toString()))
                    .andExpect(jsonPath("$.type").value("Tombstone"))
                    .andExpect(jsonPath("$.formerType").value("Note"))
                    .andExpect(jsonPath("$.deleted").isString())
                    .andExpect(content().string(not(containsString("deleted text"))));
        }
    }

    @Test
    void aDeletedFollowersOnlyOrDirectStatusLooksLikeItNeverExisted() throws Exception {
        for (var visibility : new Visibility[]{Visibility.PRIVATE, Visibility.DIRECT}) {
            var status = post(wanda, "deleted text", visibility);
            statuses.delete(status.id(), wanda.id());

            mockMvc.perform(get(path(wanda, status)).accept(ACTIVITY_JSON)).andExpect(status().isNotFound());
        }
    }

    @Test
    void aTombstoneIsOnlyServedUnderItsAuthor() throws Exception {
        var status = post(wanda, "deleted text", Visibility.PUBLIC);
        statuses.delete(status.id(), wanda.id());

        mockMvc.perform(get(path(walt, status)).accept(ACTIVITY_JSON)).andExpect(status().isNotFound());
    }

    @Test
    void aDeletedStatusHasNoPage() throws Exception {
        var status = post(wanda, "deleted text", Visibility.PUBLIC);
        statuses.delete(status.id(), wanda.id());

        mockMvc.perform(get(path(wanda, status)).accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("deleted text"))));
    }

    @Test
    void noteIsOnlyServedUnderItsAuthor() throws Exception {
        var status = post(wanda, "text from wanda", Visibility.PUBLIC);

        mockMvc.perform(get(path(walt, status)).accept(ACTIVITY_JSON)).andExpect(status().isNotFound());
        mockMvc.perform(get("/users/nobody/statuses/" + status.id()).accept(ACTIVITY_JSON))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/users/WANDA/statuses/" + status.id()).accept(ACTIVITY_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(status.uri().toString()));
    }

    @Test
    void returnsNotFoundForUnknownAndMalformedNoteIds() throws Exception {
        for (var id : new String[]{UUID.randomUUID().toString(), "nonsense", "1-1-1-1-1"}) {
            mockMvc.perform(get("/users/wanda/statuses/" + id).accept(ACTIVITY_JSON))
                    .andExpect(status().isNotFound())
                    .andExpect(header().stringValues("Vary", hasItem("Accept")));
        }
    }

    @Test
    void noteLinksToAVisibleParentAndAddressesItsAuthor() throws Exception {
        var parent = post(walt, "text from walt", Visibility.UNLISTED);
        var reply = reply(wanda, "reply to walt", parent);

        mockMvc.perform(get(path(wanda, reply)).accept(ACTIVITY_JSON))
                .andExpect(jsonPath("$.inReplyTo").value(parent.uri().toString()))
                .andExpect(jsonPath("$.cc", hasItem(walt.uri().toString())));
    }

    @Test
    void noteDoesNotAddressItsOwnAuthor() throws Exception {
        var parent = post(wanda, "first", Visibility.PUBLIC);
        var reply = reply(wanda, "second", parent);

        mockMvc.perform(get(path(wanda, reply)).accept(ACTIVITY_JSON))
                .andExpect(jsonPath("$.inReplyTo").value(parent.uri().toString()))
                .andExpect(jsonPath("$.cc.length()").value(1))
                .andExpect(jsonPath("$.cc[0]").value(wanda.uri() + "/followers"));
    }

    @Test
    void noteDoesNotRevealAHiddenParent() throws Exception {
        var parent = post(wanda, "hidden text", Visibility.PRIVATE);
        var reply = reply(wanda, "reply to myself", parent);

        mockMvc.perform(get(path(wanda, reply)).accept(ACTIVITY_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inReplyTo").doesNotHaveJsonPath())
                .andExpect(content().string(not(containsString(parent.id().toString()))));
    }

    @Test
    void noteLeavesOutADeletedParent() throws Exception {
        var parent = post(walt, "text from walt", Visibility.PUBLIC);
        var reply = reply(wanda, "reply to walt", parent);
        statuses.delete(parent.id(), walt.id());

        mockMvc.perform(get(path(wanda, reply)).accept(ACTIVITY_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inReplyTo").doesNotHaveJsonPath());
    }

    @Test
    void noteIncludesContentWarningsAsPlainText() throws Exception {
        var status = statuses.create(wanda.id(), new NewStatus(
                "behind a warning", "Tom & Jerry", null, Visibility.PUBLIC, false, null));

        mockMvc.perform(get(path(wanda, status)).accept(ACTIVITY_JSON))
                .andExpect(jsonPath("$.summary").value("Tom & Jerry"))
                .andExpect(jsonPath("$.sensitive").value(true));
    }

    @Test
    void noteCanBeSensitiveWithoutAContentWarning() throws Exception {
        var status = statuses.create(wanda.id(), new NewStatus(
                "marked sensitive", null, null, Visibility.PUBLIC, true, null));

        mockMvc.perform(get(path(wanda, status)).accept(ACTIVITY_JSON))
                .andExpect(jsonPath("$.sensitive").value(true))
                .andExpect(jsonPath("$.summary").doesNotHaveJsonPath());
    }

    @Test
    void noteIncludesTheContentUnderItsLanguage() throws Exception {
        var status = statuses.create(wanda.id(), new NewStatus(
                "bonjour", null, "fr", Visibility.PUBLIC, false, null));

        mockMvc.perform(get(path(wanda, status)).accept(ACTIVITY_JSON))
                .andExpect(jsonPath("$.contentMap.fr").value(status.content()));
    }

    @Test
    void choosesBetweenTheStatusPageAndTheNote() throws Exception {
        var status = post(wanda, "public text", Visibility.PUBLIC);

        // The actor document, which declares the same types, is tried with every form of them.
        for (var accept : new String[]{"application/activity+json", "application/activity+json, */*;q=0.1"}) {
            mockMvc.perform(get(path(wanda, status)).header("Accept", accept))
                    .andExpect(status().isOk())
                    .andExpect(header().stringValues("Vary", hasItem("Accept")))
                    .andExpect(jsonPath("$.type").value("Note"));
        }
        for (var accept : new String[]{BROWSER_ACCEPT, "text/html", "*/*"}) {
            mockMvc.perform(get(path(wanda, status)).header("Accept", accept))
                    .andExpect(status().isOk())
                    .andExpect(header().stringValues("Vary", hasItem("Accept")))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
        }
    }

    @Test
    void allowsCrossOriginAndHeadRequestsForStatusUrls() throws Exception {
        var status = post(wanda, "public text", Visibility.PUBLIC);

        mockMvc.perform(options(path(wanda, status))
                        .header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        for (var accept : new String[]{"application/activity+json", "*/*"}) {
            mockMvc.perform(get(path(wanda, status)).header("Accept", accept).header("Origin", "https://client.example"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        }
        mockMvc.perform(head(path(wanda, status)).accept(ACTIVITY_JSON)).andExpect(status().isOk());
    }

    // Rendering

    @Test
    void escapesStatusTextAndContentWarnings() throws Exception {
        var status = statuses.create(wanda.id(), new NewStatus(
                "<script>alert('text')</script>", "<script>alert('warning')</script>", null, Visibility.PUBLIC, false, null));

        mockMvc.perform(get(path(wanda, status)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<script>"))))
                .andExpect(content().string(containsString("&lt;script&gt;")));
    }

    @Test
    void keepsLinksInStatusText() throws Exception {
        var status = post(wanda, "See https://example.com/page", Visibility.PUBLIC);

        mockMvc.perform(get(path(wanda, status)))
                .andExpect(content().string(containsString("<a href=\"https://example.com/page\"")));
    }

    @Test
    void foldsSensitiveStatuses() throws Exception {
        var warned = statuses.create(wanda.id(), new NewStatus(
                "behind a warning", "Spoilers", null, Visibility.PUBLIC, false, null));
        var sensitive = statuses.create(wanda.id(), new NewStatus(
                "marked sensitive", null, null, Visibility.PUBLIC, true, null));
        var plain = post(wanda, "nothing to hide", Visibility.PUBLIC);

        mockMvc.perform(get(path(wanda, warned)))
                .andExpect(content().string(allOf(
                        containsString("<details>"), containsString("<summary>Spoilers</summary>"),
                        containsString("behind a warning"))));
        mockMvc.perform(get(path(wanda, sensitive)))
                .andExpect(content().string(containsString("<summary>Sensitive content</summary>")));
        mockMvc.perform(get(path(wanda, plain)))
                .andExpect(content().string(not(containsString("<details>"))));
    }

    @Test
    void marksTheLanguageOfAStatus() throws Exception {
        var status = statuses.create(wanda.id(), new NewStatus(
                "bonjour", null, "fr", Visibility.PUBLIC, false, null));

        mockMvc.perform(get(path(wanda, status)))
                .andExpect(content().string(containsString("lang=\"fr\"")));
    }

    @Test
    void doesNotAssumeTheLanguageOfAStatusWithoutOne() throws Exception {
        var status = post(wanda, "no language set", Visibility.PUBLIC);

        mockMvc.perform(get(path(wanda, status)))
                .andExpect(content().string(containsString("lang=\"und\"")));
    }

    @Test
    void profileDoesNotCountPostsItCannotShow() throws Exception {
        post(wanda, "private text", Visibility.PRIVATE);

        mockMvc.perform(get("/users/wanda"))
                .andExpect(content().string(containsString("No posts to show.")))
                .andExpect(content().string(not(containsString("Posts:"))));
    }

    private void expectProfilePage(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().stringValues("Vary", hasItem("Accept")))
                .andExpect(content().string(containsString("@wanda@elysium.example")));
    }

    private Actor account(String username) {
        actors.createLocalIfAbsent(username, actor -> {
        });
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
