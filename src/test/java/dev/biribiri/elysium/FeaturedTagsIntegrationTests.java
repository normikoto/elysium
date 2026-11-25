package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorDataRemoval;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.instance.base-url=https://elysium.example")
@AutoConfigureMockMvc
class FeaturedTagsIntegrationTests {
    private static Instant started;


    private static final Set<String> SCOPES = Set.of("read:accounts", "write:accounts", "read:statuses");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ActorService actors;
    @Autowired
    private StatusService statuses;
    @Autowired
    private BoostService boosts;
    @Autowired
    private List<ActorDataRemoval> removals;
    @Autowired
    private JdbcClient jdbc;
    private Actor alice;
    private Actor bob;

    @BeforeEach
    void setUp() {
        settled();
        jdbc.sql("DELETE FROM status").update();
        jdbc.sql("DELETE FROM featured_tag").update();
        alice = local("ftalice");
        bob = local("ftbob");
    }

    @Test
    void featuresAndUnfeaturesATag() throws Exception {
        var id = JsonPath.<String>read(feature(alice, "#Cats").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("cats"))
                .andExpect(jsonPath("$.url").value("https://elysium.example/tags/cats"))
                .andExpect(jsonPath("$.statuses_count").value("0"))
                .andExpect(jsonPath("$.last_status_at").value(nullValue()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        feature(alice, "cats").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));

        mvc.perform(get("/api/v1/tags/cats").with(user(alice))).andExpect(jsonPath("$.featuring").value(true));
        mvc.perform(get("/api/v1/tags/cats").with(user(bob))).andExpect(jsonPath("$.featuring").value(false));
        mvc.perform(get("/api/v1/tags/cats")).andExpect(jsonPath("$.featuring").doesNotExist());
        mvc.perform(get("/api/v1/featured_tags").with(user(alice))).andExpect(jsonPath("$[*].id").value(contains(id)));

        mvc.perform(delete("/api/v1/featured_tags/" + id).with(user(bob))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/featured_tags/" + id).with(user(alice)))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));
        mvc.perform(delete("/api/v1/featured_tags/" + id).with(user(alice))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/featured_tags/nope").with(user(alice))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/featured_tags").with(user(alice))).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void readsTheNameFromJson() throws Exception {
        mvc.perform(post("/api/v1/featured_tags").with(user(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"dogs\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("dogs"));
    }

    @Test
    void refusesInvalidNamesAndMoreThanTheLimit() throws Exception {
        feature(alice, "123").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("Validation failed: Tag is invalid"));
        mvc.perform(post("/api/v1/featured_tags").with(user(alice))).andExpect(status().isUnprocessableContent());
        for (var i = 0; i < 10; i++) {
            feature(alice, "tag" + i).andExpect(status().isOk());
        }
        feature(alice, "tag10").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("maximum number of hashtags")));
        feature(alice, "tag3").andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM featured_tag").query(Long.class).single()).isEqualTo(10);
    }

    @Test
    void needsScopesAndAUser() throws Exception {
        mvc.perform(get("/api/v1/featured_tags")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/featured_tags").param("name", "cats")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/featured_tags").with(token(alice.id(), Set.of("write:accounts"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/featured_tags").param("name", "cats")
                        .with(token(alice.id(), Set.of("read:accounts"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/featured_tags/suggestions").with(token(alice.id(), Set.of("write:accounts"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/featured_tags").param("name", "cats").with(token(null, SCOPES)))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(get("/api/v1/featured_tags").with(token(null, SCOPES)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void countsTheAccountsPublicAndUnlistedStatusesMostUsedFirst() throws Exception {
        publish(alice, "#cats", Visibility.PUBLIC);
        var newest = publish(alice, "#Cats again", Visibility.UNLISTED);
        publish(alice, "#cats for followers", Visibility.PRIVATE);
        publish(alice, "#cats @ftbob", Visibility.DIRECT);
        publish(alice, "#dogs", Visibility.PUBLIC);
        boosts.reblog(publish(bob, "#cats #dogs", Visibility.PUBLIC).id(), alice.id(), Visibility.PUBLIC).orElseThrow();
        settled();
        feature(alice, "dogs").andExpect(status().isOk()).andExpect(jsonPath("$.statuses_count").value("1"));
        feature(alice, "birds").andExpect(status().isOk());
        feature(alice, "cats").andExpect(status().isOk())
                .andExpect(jsonPath("$.statuses_count").value("2"))
                .andExpect(jsonPath("$.last_status_at")
                        .value(LocalDate.ofInstant(newest.publishedAt(), ZoneOffset.UTC).toString()));

        mvc.perform(get("/api/v1/accounts/" + alice.id() + "/featured_tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(contains("cats", "dogs", "birds")));
        mvc.perform(get("/api/v1/accounts/" + bob.id() + "/featured_tags").with(user(alice)))
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/accounts/" + UUID.randomUUID() + "/featured_tags")).andExpect(status().isNotFound());
    }

    @Test
    void suggestsRecentlyUsedTagsThatArentFeatured() throws Exception {
        publish(alice, "#cats #dogs", Visibility.PUBLIC);
        publish(alice, "#birds", Visibility.UNLISTED);
        publish(alice, "#secret", Visibility.PRIVATE);
        publish(bob, "#fish", Visibility.PUBLIC);
        feature(alice, "dogs").andExpect(status().isOk());

        mvc.perform(get("/api/v1/featured_tags/suggestions").with(user(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(contains("birds", "cats")))
                .andExpect(jsonPath("$[0].history", hasSize(7)))
                .andExpect(jsonPath("$[0].featuring").value(false));
    }

    @Test
    void listsAnAccountsStatusesWithATag() throws Exception {
        var first = publish(alice, "#cats", Visibility.PUBLIC);
        publish(alice, "#dogs", Visibility.PUBLIC);
        var second = publish(alice, "#Cats", Visibility.PUBLIC);
        publish(bob, "#cats", Visibility.PUBLIC);
        boosts.reblog(publish(bob, "#cats boosted", Visibility.PUBLIC).id(), alice.id(), Visibility.PUBLIC)
                .orElseThrow();
        settled();
        var path = "/api/v1/accounts/" + alice.id() + "/statuses";

        mvc.perform(get(path).param("tagged", "#CATS"))
                .andExpect(jsonPath("$[*].id").value(contains(second.id().toString(), first.id().toString())));
        mvc.perform(get(path).param("tagged", "cats").param("limit", "1"))
                .andExpect(jsonPath("$[*].id").value(contains(second.id().toString())))
                .andExpect(header().string("Link", containsString("tagged=cats")));
        mvc.perform(get(path).param("tagged", "123")).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void anAccountsFeaturedTagsGoWhenItsDeleted() throws Exception {
        feature(alice, "cats").andExpect(status().isOk());
        feature(bob, "cats").andExpect(status().isOk());

        removals.forEach(removal -> removal.removeForDeleted(alice.id()));

        assertThat(jdbc.sql("SELECT account_id FROM featured_tag").query(UUID.class).list()).containsExactly(bob.id());
    }

    private ResultActions feature(Actor actor, String name) throws Exception {
        return mvc.perform(post("/api/v1/featured_tags").param("name", name).with(user(actor)));
    }

    private Status publish(Actor author, String text, Visibility visibility) {
        var status = statuses.create(author.id(), new NewStatus(text, null, null, visibility, false, null));
        settled();
        return status;
    }

    private Actor local(String name) {
        return actors.findLocalByUsername(name).orElseGet(() -> actors.createLocalIfAbsent(name, actor -> {}).orElseThrow());
    }

    private static RequestPostProcessor user(Actor actor) {
        return token(actor.id(), SCOPES);
    }

    private static RequestPostProcessor token(UUID actor, Set<String> scopes) {
        return opaqueToken().principal(new TokenPrincipal("test", Optional.ofNullable(actor), scopes,
                Instant.now(), Instant.now().plusSeconds(3600), "test"));
    }

    @BeforeAll
    static void rememberTheStart() {
        started = Instant.now();
    }

    private void settled() {
        TestEvents.awaitHandled(jdbc, started);
    }
}
