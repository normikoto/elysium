package dev.biribiri.elysium;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.ProfileChange;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.RemoteEdit;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.RemoteStatuses;
import dev.biribiri.elysium.content.Visibility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.opaqueToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.instance.base-url=https://trends.elysium.example")
@AutoConfigureMockMvc
class TrendsIntegrationTests {

    private static final String BASE = "/api/v1/trends/";
    private static final String STATUSES = "/api/v1/statuses";
    private static Instant started;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;
    @Autowired
    private ProfileService profiles;
    @Autowired
    private RemoteStatuses remoteStatuses;
    @Autowired
    private JsonMapper json;

    private Actor alice;
    private Actor bob;
    private Actor carol;
    private Actor viewer;

    @BeforeAll
    static void rememberTheStart() {
        started = Instant.now();
    }

    @BeforeEach
    void setUp() {
        TestEvents.awaitHandled(jdbc, started);
        jdbc.sql("DELETE FROM status").update();
        jdbc.sql("DELETE FROM tag_follow").update();
        jdbc.sql("DELETE FROM featured_tag").update();
        jdbc.sql("DELETE FROM post_filter").update();
        jdbc.sql("DELETE FROM account_block").update();
        jdbc.sql("DELETE FROM account_mute").update();
        jdbc.sql("DELETE FROM domain_block").update();
        jdbc.sql("UPDATE actor SET suspended_at = NULL").update();
        jdbc.sql("UPDATE profile SET discoverable = false").update();
        alice = local("trendalice");
        bob = local("trendbob");
        carol = local("trendcarol");
        viewer = local("trendviewer");
    }

    @Test
    void ranksTagsByDistinctAuthorsAndPaginatesWithHistoryAndRelationships() throws Exception {
        var yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        published(create(alice, "#Cats #Birds #Solo"), yesterday, 1);
        published(create(alice, "#Cats #Solo"), yesterday, 2);
        published(create(bob, "#Cats #Birds"), yesterday, 3);
        published(create(carol, "#Cats"), yesterday, 4);
        published(create(alice, "#Expired"), yesterday.minusDays(3), 1);
        published(create(bob, "#Expired"), yesterday.minusDays(3), 2);

        mvc.perform(post("/api/v1/tags/cats/follow").with(token(viewer, "write:follows")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/featured_tags").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("name", "cats").with(token(viewer, "write:accounts")))
                .andExpect(status().isOk());

        mvc.perform(get(BASE + "tags").param("limit", "1").param("offset", "-1"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.LINK,
                        "<https://trends.elysium.example/api/v1/trends/tags?limit=1&offset=1>; rel=\"next\""))
                .andExpect(jsonPath("$[*].name", contains("cats")))
                .andExpect(jsonPath("$[0].url").value("https://trends.elysium.example/tags/cats"))
                .andExpect(jsonPath("$[0].history", hasSize(7)))
                .andExpect(jsonPath("$[0].history[0].uses").value("0"))
                .andExpect(jsonPath("$[0].history[1].day")
                        .value(Long.toString(yesterday.atStartOfDay(ZoneOffset.UTC).toEpochSecond())))
                .andExpect(jsonPath("$[0].history[1].uses").value("4"))
                .andExpect(jsonPath("$[0].history[1].accounts").value("3"))
                .andExpect(jsonPath("$[0].following").doesNotHaveJsonPath());
        mvc.perform(get(BASE + "tags").with(token(viewer, "write:statuses")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("cats", "birds")))
                .andExpect(jsonPath("$[0].following").value(true))
                .andExpect(jsonPath("$[0].featuring").value(true));
        mvc.perform(get(BASE + "tags").param("limit", "1").param("offset", "1").with(token(null, "write:statuses")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("birds")))
                .andExpect(header().string(HttpHeaders.LINK, containsString("offset=0>; rel=\"prev\"")));
    }

    @Test
    void excludesNonpublicAndModeratedSharesBeforePagingAndCounting() throws Exception {
        var host = "blocked.trends.example";
        var blocked = remoteActor(host);
        storeRemote(blocked, "<p>Blocked</p>", Set.of("moderated"));
        create(alice, "#moderated #kept #muted #private #localonly");
        create(bob, "#kept #muted #suspended");
        create(carol, "#suspended");
        mvc.perform(postStatus(bob, "#private").param("visibility", "private")).andExpect(status().isOk());
        mvc.perform(postStatus(bob, "#localonly").param("local_only", "true")).andExpect(status().isOk());
        jdbc.sql("INSERT INTO domain_block (id, domain) VALUES (:id, :host)")
                .param("id", UUID.randomUUID()).param("host", host).update();
        jdbc.sql("UPDATE actor SET suspended_at = now() WHERE id = :id").param("id", carol.id()).update();

        mvc.perform(get(BASE + "tags")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("kept", "muted")));
        mvc.perform(post("/api/v1/accounts/" + bob.id() + "/mute").with(token(viewer, "write:mutes")))
                .andExpect(status().isOk());
        mvc.perform(get(BASE + "tags").with(token(viewer, "write:statuses"))).andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void ranksDiscoverableOriginalStatusesByDistinctRecentEngagers() throws Exception {
        discoverable(alice);
        var top = create(alice, "Hide this marker");
        var second = create(alice, "Second");
        var single = create(alice, "One other account");
        var expired = create(alice, "Old engagement");
        var undiscoverable = create(bob, "Opted out");
        var reply = create(postStatus(alice, "A reply").param("in_reply_to_id", top));
        var sensitive = create(postStatus(alice, "Sensitive").param("sensitive", "true"));
        var warning = create(postStatus(alice, "A warning").param("spoiler_text", "Warning"));
        var localOnly = create(postStatus(alice, "Only here").param("local_only", "true"));

        for (var id : List.of(top, second, single, expired, reply, sensitive, warning, localOnly)) {
            favourite(bob, id);
        }
        for (var id : List.of(top, expired, undiscoverable, reply, sensitive, warning, localOnly)) {
            favourite(carol, id);
        }
        favourite(alice, single);
        favourite(alice, undiscoverable);
        favourite(viewer, top);
        boost(bob, single);
        boost(carol, second);
        jdbc.sql("UPDATE status_like SET activity_at = now() - interval '3 days' WHERE status_id = :id")
                .param("id", UUID.fromString(expired)).update();

        mvc.perform(get(BASE + "statuses")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(top, second)));
        mvc.perform(get(BASE + "statuses").param("limit", "1").param("offset", "1")
                        .with(token(carol, "write:accounts")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[0].favourited").value(false))
                .andExpect(jsonPath("$[0].reblogged").value(true));
        mvc.perform(post("/api/v2/filters").with(token(viewer, "write:filters"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"title": "Hidden", "context": ["public"], "filter_action": "hide",
                         "keywords_attributes": [{"keyword": "marker"}]}
                        """))
                .andExpect(status().isOk());
        mvc.perform(get(BASE + "statuses").param("limit", "1").with(token(viewer, "write:accounts")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty())
                .andExpect(header().string(HttpHeaders.LINK, containsString("offset=1>; rel=\"next\"")));
    }

    @Test
    void quotesOnlyTrendWhileTheQuotedPostIsEligible() throws Exception {
        discoverable(alice);
        var original = create(bob, "Original post");
        var quote = create(postStatus(alice, "Quote").param("quoted_status_id", original));
        favourite(carol, quote);
        favourite(viewer, quote);

        mvc.perform(get(BASE + "statuses")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        discoverable(bob);
        mvc.perform(get(BASE + "statuses")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(quote)))
                .andExpect(jsonPath("$[0].quote.quoted_status.id").value(original));

        mvc.perform(delete(STATUSES + "/" + original).with(token(bob, "write:statuses")))
                .andExpect(status().isOk());
        mvc.perform(get(BASE + "statuses")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void indexesRenderedLinksAndUpdatesThemOnLocalAndRemoteEditsAndDeletion() throws Exception {
        var yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        var firstUrl = "https://news.example/story?q=one&lang=en";
        var secondUrl = "https://news.example/another";
        var remote = remoteActor("remote.trends.example");
        var localId = create(alice, "First " + firstUrl + "#part " + firstUrl);
        var remoteUri = storeRemote(remote, """
                <p><a href="HTTPS://NEWS.EXAMPLE:443/story?q=one&amp;lang=en">Read</a>
                <a href="https://people.example/name" class="mention">Name</a></p>
                <p class="quote-inline"><a href="https://posts.example/quoted">Quoted</a></p>
                """, Set.of());
        published(localId, yesterday, 1);
        jdbc.sql("UPDATE status SET published_at = :at WHERE uri = :uri")
                .param("at", yesterday.atStartOfDay().atOffset(ZoneOffset.UTC).plusMinutes(2))
                .param("uri", remoteUri.toString()).update();
        create(postStatus(bob, "Warned " + firstUrl).param("spoiler_text", "Warning"));

        mvc.perform(get(BASE + "links").param("limit", "0")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].url", contains(firstUrl)))
                .andExpect(jsonPath("$[0].title").value(firstUrl))
                .andExpect(jsonPath("$[0].provider_name").value("news.example"))
                .andExpect(jsonPath("$[0].type").value("link"))
                .andExpect(jsonPath("$[0].image").value(nullValue()))
                .andExpect(jsonPath("$[0].history", hasSize(7)))
                .andExpect(jsonPath("$[0].history[0].uses").value("0"))
                .andExpect(jsonPath("$[0].history[1].uses").value("2"))
                .andExpect(jsonPath("$[0].history[1].accounts").value("2"));
        assertThat(jdbc.sql("SELECT url FROM status_link").query(String.class).list())
                .containsExactlyInAnyOrder(firstUrl, firstUrl);

        mvc.perform(put(STATUSES + "/" + localId).with(token(alice, "write:statuses"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", "Edited " + secondUrl))
                .andExpect(status().isOk());
        mvc.perform(get(BASE + "links")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        assertThat(remoteStatuses.updateRemote(new RemoteEdit(remoteUri, remote.id(),
                "<p><a href=\"" + secondUrl + "\">Edited</a></p>", null, null, false, Instant.now())))
                .isEqualTo(RemoteEdit.Outcome.EDITED);
        mvc.perform(get(BASE + "links").with(token(viewer, "write:accounts"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].url", contains(secondUrl)))
                .andExpect(jsonPath("$[0].missing_attribution").value(false));

        mvc.perform(delete(STATUSES + "/" + localId).with(token(alice, "write:statuses")))
                .andExpect(status().isOk());
        mvc.perform(get(BASE + "links")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        assertThat(jdbc.sql("SELECT url FROM status_link").query(String.class).list()).containsExactly(secondUrl);
    }

    @Test
    void validatesPageParametersAndRejectsInvalidBearerTokens() throws Exception {
        for (var endpoint : List.of("tags", "statuses", "links")) {
            mvc.perform(get(BASE + endpoint).param("limit", "many")).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("Validation failed: limit must be a number"));
            mvc.perform(get(BASE + endpoint).param("offset", "many")).andExpect(status().isUnprocessableContent());
            mvc.perform(get(BASE + endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
                    .andExpect(status().isUnauthorized());
        }
    }

    private String create(Actor actor, String text) throws Exception {
        return create(postStatus(actor, text));
    }

    private String create(MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asString();
    }

    private static MockHttpServletRequestBuilder postStatus(Actor actor, String text) {
        return post(STATUSES).with(token(actor, "write:statuses"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("status", text);
    }

    private void published(String id, LocalDate day, int minute) {
        jdbc.sql("UPDATE status SET published_at = :at WHERE id = :id")
                .param("at", day.atStartOfDay().atOffset(ZoneOffset.UTC).plusMinutes(minute))
                .param("id", UUID.fromString(id)).update();
    }

    private void discoverable(Actor actor) {
        profiles.update(actor.id(), ProfileChange.NONE.withDiscoverable(true));
    }

    private void favourite(Actor actor, String id) throws Exception {
        mvc.perform(post(STATUSES + "/" + id + "/favourite").with(token(actor, "write:favourites")))
                .andExpect(status().isOk());
    }

    private void boost(Actor actor, String id) throws Exception {
        mvc.perform(post(STATUSES + "/" + id + "/reblog").with(token(actor, "write:statuses"))
                        .param("visibility", "public"))
                .andExpect(status().isOk());
    }

    private Actor local(String name) {
        return actors.findLocalByUsername(name).orElseGet(() -> actors.createLocalIfAbsent(name, actor -> {}).orElseThrow());
    }

    private Actor remoteActor(String host) {
        var uri = URI.create("https://" + host + "/users/alice");
        return actors.upsertRemote(new RemoteActorProfile(uri, "alice", host, URI.create(uri + "/inbox"),
                null, null, null, null));
    }

    private URI storeRemote(Actor remote, String html, Set<String> tags) {
        var uri = URI.create(remote.uri() + "/notes/" + UUID.randomUUID());
        remoteStatuses.storeRemote(new RemoteStatus(uri, null, remote.id(), html, null, null, Visibility.PUBLIC,
                false, null, Instant.now().minusSeconds(10), null, Map.of(), tags)).orElseThrow();
        return uri;
    }

    private static RequestPostProcessor token(Actor actor, String scope) {
        var now = Instant.now();
        return opaqueToken().principal(new TokenPrincipal("trends-test", Optional.ofNullable(actor).map(Actor::id),
                Set.of(scope), now, now.plusSeconds(3600), "trends-test"));
    }
}
