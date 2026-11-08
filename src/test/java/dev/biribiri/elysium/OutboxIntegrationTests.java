package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Outboxes, as other servers read them to backfill an account's statuses.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://outbox.example", "elysium.jobs.worker-enabled=false"})
@AutoConfigureMockMvc
class OutboxIntegrationTests {

    private static final String ACTIVITY_JSON = "application/activity+json";
    private static final String PUBLIC = "https://www.w3.org/ns/activitystreams#Public";

    @Autowired MockMvc mockMvc;
    @Autowired ActorService actors;
    @Autowired StatusService statuses;
    @Autowired BoostService boosts;

    private Actor alice;
    private String path;

    @BeforeEach
    void setUp() {
        alice = local();
        path = "/users/" + alice.username() + "/outbox";
    }

    @Test
    void actorDocumentLinksToAPagedCollection() throws Exception {
        mockMvc.perform(get("/users/" + alice.username()).accept(ACTIVITY_JSON))
                .andExpect(jsonPath("$.outbox").value(alice.uri() + "/outbox"));
        post(alice, "Hello", Visibility.PUBLIC);

        mockMvc.perform(get(path).accept(ACTIVITY_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alice.uri() + "/outbox"))
                .andExpect(jsonPath("$.type").value("OrderedCollection"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.first").value(alice.uri() + "/outbox?page=true"));
    }

    @Test
    void listsPublicAndUnlistedStatusesAsCreatesThatEmbedTheirNote() throws Exception {
        var unlisted = post(alice, "Unlisted", Visibility.UNLISTED);
        post(alice, "Followers only", Visibility.PRIVATE);
        post(alice, "Direct", Visibility.DIRECT);
        var first = post(alice, "Public", Visibility.PUBLIC);

        var page = mockMvc.perform(get(path).param("page", "true").accept(ACTIVITY_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("OrderedCollectionPage"))
                .andExpect(jsonPath("$.partOf").value(alice.uri() + "/outbox"))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.orderedItems[*].id", contains(first.uri() + "#create", unlisted.uri() + "#create")))
                .andExpect(jsonPath("$.orderedItems[0].type").value("Create"))
                .andExpect(jsonPath("$.orderedItems[0].actor").value(alice.uri().toString()))
                .andExpect(jsonPath("$.orderedItems[0]['@context']").doesNotExist())
                .andExpect(jsonPath("$.orderedItems[0].to", contains(PUBLIC)))
                .andExpect(jsonPath("$.orderedItems[1].cc", contains(PUBLIC)))
                .andExpect(jsonPath("$.next").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // The same Note as a fetch of it serves.
        var note = mockMvc.perform(get(first.uri()).accept(ACTIVITY_JSON)).andReturn().getResponse().getContentAsString();
        var embedded = JsonPath.<Map<String, Object>>read(page, "$.orderedItems[0].object");
        var fetched = JsonPath.<Map<String, Object>>read(note, "$");
        fetched.remove("@context");
        assertThat(embedded).isEqualTo(fetched);
    }

    @Test
    void listsPublicBoostsAsAnnounces() throws Exception {
        var bob = local();
        var boosted = post(bob, "Boost me", Visibility.PUBLIC);
        var hidden = post(bob, "Don't", Visibility.PUBLIC);
        var boost = boosts.reblog(boosted.id(), alice.id(), Visibility.PUBLIC).orElseThrow();
        boosts.reblog(hidden.id(), alice.id(), Visibility.PRIVATE).orElseThrow();

        mockMvc.perform(get(path).param("page", "true").accept(ACTIVITY_JSON))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.orderedItems", hasSize(1)))
                .andExpect(jsonPath("$.orderedItems[0].id").value(boost.uri().toString()))
                .andExpect(jsonPath("$.orderedItems[0].type").value("Announce"))
                .andExpect(jsonPath("$.orderedItems[0].object").value(boosted.uri().toString()))
                .andExpect(jsonPath("$.orderedItems[0].cc", contains(alice.uri() + "/followers", bob.uri().toString())));
    }

    @Test
    void pagesLinkToTheNext() throws Exception {
        var oldest = post(alice, "0", Visibility.PUBLIC);
        for (var i = 1; i < 41; i++) {
            post(alice, String.valueOf(i), Visibility.PUBLIC);
        }

        var first = mockMvc.perform(get(path).param("page", "true").accept(ACTIVITY_JSON))
                .andExpect(jsonPath("$.orderedItems", hasSize(40)))
                .andExpect(jsonPath("$.totalItems").value(41))
                .andExpect(jsonPath("$.next", startsWith(alice.uri() + "/outbox?page=true&max_id=")))
                .andReturn().getResponse().getContentAsString();
        var next = URI.create(JsonPath.<String>read(first, "$.next"));

        mockMvc.perform(get(next).accept(ACTIVITY_JSON))
                .andExpect(jsonPath("$.id").value(next.toString()))
                .andExpect(jsonPath("$.orderedItems[*].id", contains(oldest.uri() + "#create")))
                .andExpect(jsonPath("$.next").doesNotExist());
        mockMvc.perform(get(path).param("page", "true").param("max_id", "nope").accept(ACTIVITY_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void servesJsonToAnyoneAndNoWebPage() throws Exception {
        mockMvc.perform(get(path).accept(MediaType.ALL))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith(ACTIVITY_JSON)));
        mockMvc.perform(get(path).accept("application/ld+json; profile=\"https://www.w3.org/ns/activitystreams\""))
                .andExpect(status().isOk());
        mockMvc.perform(get(path).accept(MediaType.TEXT_HTML)).andExpect(status().isNotAcceptable());
        mockMvc.perform(get(path).header("Origin", "https://elsewhere.example").accept(ACTIVITY_JSON))
                .andExpect(header().string("Access-Control-Allow-Origin", "*"));
        mockMvc.perform(get("/users/nobody" + UUID.randomUUID().toString().substring(0, 8) + "/outbox").accept(ACTIVITY_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void theInstanceActorsIsEmpty() throws Exception {
        var outbox = mockMvc.perform(get("/actor").accept(ACTIVITY_JSON)).andReturn().getResponse().getContentAsString();
        var uri = JsonPath.<String>read(outbox, "$.outbox");

        mockMvc.perform(get(URI.create(uri)).accept(ACTIVITY_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(uri))
                .andExpect(jsonPath("$.type").value("OrderedCollection"))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    private Actor local() {
        var name = "ob" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return actors.createLocalIfAbsent(name, actor -> {}).orElseThrow();
    }

    private Status post(Actor author, String text, Visibility visibility) {
        return statuses.create(author.id(), new NewStatus(text, null, null, visibility, false, null));
    }
}
