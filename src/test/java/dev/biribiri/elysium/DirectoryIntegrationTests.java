package dev.biribiri.elysium;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.ProfileChange;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.actor.RemoteProfile;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.graph.MuteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.opaqueToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.instance.base-url=https://directory.example")
@AutoConfigureMockMvc
class DirectoryIntegrationTests {

    private static final String DIRECTORY = "/api/v1/directory";
    private static final Set<String> SCOPES = Set.of("read:accounts", "write:statuses", "write:blocks");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ActorService actors;
    @Autowired
    private ProfileService profiles;
    @Autowired
    private MuteService mutes;
    @Autowired
    private JdbcClient jdbc;

    private Actor alice;
    private Actor bob;
    private Actor carol;

    @BeforeEach
    void setUp() {
        settled();
        jdbc.sql("DELETE FROM status").update();
        jdbc.sql("DELETE FROM account_block").update();
        jdbc.sql("DELETE FROM account_mute").update();
        jdbc.sql("DELETE FROM domain_block").update();
        jdbc.sql("UPDATE actor SET suspended_at = NULL").update();
        jdbc.sql("UPDATE profile SET discoverable = false, moved_to_uri = NULL").update();
        alice = local("diralice");
        bob = local("dirbob");
        carol = local("dircarol");
    }

    @Test
    void listsOnlyTheAccountsThatChooseToBeListed() throws Exception {
        discoverable(alice);
        discoverable(bob);

        mvc.perform(get(DIRECTORY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasSize(2)))
                .andExpect(jsonPath("$[*].id", not(contains(carol.id().toString()))))
                .andExpect(jsonPath("$[0].discoverable").value(true));
    }

    @Test
    void listsThoseThatPostedLastFirst() throws Exception {
        discoverable(alice);
        discoverable(bob);
        discoverable(carol);
        publish(bob, "first");
        publish(alice, "second");

        mvc.perform(get(DIRECTORY))
                .andExpect(jsonPath("$[*].id", contains(
                        alice.id().toString(), bob.id().toString(), carol.id().toString())));
        mvc.perform(get(DIRECTORY).param("order", "active").param("offset", "1").param("limit", "1"))
                .andExpect(jsonPath("$[*].id", contains(bob.id().toString())));
    }

    @Test
    void listsTheNewestFirst() throws Exception {
        discoverable(alice);
        discoverable(bob);
        var dave = local("dirdave" + UUID.randomUUID().toString().substring(0, 8));
        discoverable(dave);

        mvc.perform(get(DIRECTORY).param("order", "new").param("limit", "1"))
                .andExpect(jsonPath("$[*].id", contains(dave.id().toString())));
    }

    @Test
    void listsAccountsOnOtherServersUnlessOnlyLocalOnesAreAsked() throws Exception {
        discoverable(alice);
        var remote = remote("remote.directory.example", true);
        remote("remote.directory.example", false);

        mvc.perform(get(DIRECTORY))
                .andExpect(jsonPath("$[*].id", hasSize(2)))
                .andExpect(jsonPath("$[?(@.id == '" + remote.id() + "')].acct")
                        .value(contains(containsString("@remote.directory.example"))));
        mvc.perform(get(DIRECTORY).param("local", "true"))
                .andExpect(jsonPath("$[*].id", contains(alice.id().toString())));
    }

    @Test
    void leavesOutSuspendedMovedAndBlockedAccounts() throws Exception {
        discoverable(alice);
        discoverable(bob);
        discoverable(carol);
        var blocked = remote("blocked.directory.example", true);
        jdbc.sql("UPDATE actor SET suspended_at = now() WHERE id = :id").param("id", bob.id()).update();
        jdbc.sql("UPDATE profile SET moved_to_uri = 'https://elsewhere.example/users/c' WHERE actor_id = :id")
                .param("id", carol.id()).update();
        jdbc.sql("INSERT INTO domain_block (id, domain) VALUES (:id, 'blocked.directory.example')")
                .param("id", UUID.randomUUID()).update();

        mvc.perform(get(DIRECTORY))
                .andExpect(jsonPath("$[*].id", contains(alice.id().toString())));
        jdbc.sql("DELETE FROM domain_block").update();
        mvc.perform(get(DIRECTORY))
                .andExpect(jsonPath("$[*].id", hasSize(2)))
                .andExpect(jsonPath("$[*].id", hasItem(blocked.id().toString())));
    }

    @Test
    void leavesOutWhoTheViewerHidesAndWhoHidesFromThem() throws Exception {
        discoverable(alice);
        discoverable(bob);
        discoverable(carol);
        mutes.mute(alice.id(), bob.id(), true, Optional.empty());
        mvc.perform(post("/api/v1/accounts/" + alice.id() + "/block").with(user(carol)))
                .andExpect(status().isOk());
        settled();

        mvc.perform(get(DIRECTORY).with(user(alice)))
                .andExpect(jsonPath("$[*].id", contains(alice.id().toString())));
        mvc.perform(get(DIRECTORY))
                .andExpect(jsonPath("$[*].id", hasSize(3)));
    }

    @Test
    void refusesWhatItCantRead() throws Exception {
        mvc.perform(get(DIRECTORY).param("order", "popular"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(get(DIRECTORY).param("limit", "many"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(get(DIRECTORY).param("offset", "-5").param("limit", "1000"))
                .andExpect(status().isOk());
    }

    @Test
    void thePageListsThisServersAccounts() throws Exception {
        discoverable(alice);
        profiles.update(alice.id(), ProfileChange.NONE.withDisplayName("Alice Directory").withNote("Hello <there>"));
        remote("page.directory.example", true);

        mvc.perform(get("/directory"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Alice Directory")))
                .andExpect(content().string(containsString("@diralice")))
                .andExpect(content().string(containsString("Hello &lt;there&gt;")))
                .andExpect(content().string(not(containsString("page.directory.example"))))
                .andExpect(content().string(not(containsString("dirbob"))));
        mvc.perform(get("/directory").param("order", "new")).andExpect(status().isOk());
        mvc.perform(get("/directory").param("order", "popular")).andExpect(status().isNotFound());
        mvc.perform(get("/directory").param("page", "0")).andExpect(status().isNotFound());
        mvc.perform(get("/")).andExpect(content().string(containsString("href=\"/directory\"")));
    }

    private void discoverable(Actor actor) {
        profiles.update(actor.id(), ProfileChange.NONE.withDiscoverable(true));
        settled();
    }

    private void publish(Actor actor, String text) throws Exception {
        mvc.perform(post("/api/v1/statuses").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", text).with(user(actor)))
                .andExpect(status().isOk());
    }

    private Actor local(String name) {
        return actors.findLocalByUsername(name)
                .orElseGet(() -> actors.createLocalIfAbsent(name, actor -> {}).orElseThrow());
    }

    private Actor remote(String domain, boolean discoverable) {
        var uri = URI.create("https://" + domain + "/users/" + UUID.randomUUID());
        var profile = new RemoteProfile("", "", "", null, null, List.of(), false, false, discoverable, false, false,
                false, null, null);
        return actors.upsertRemote(new RemoteActorProfile(uri, "r", domain, URI.create(uri + "/inbox"), null, null, null,
                URI.create(uri + "/followers"), null, profile));
    }

    private static RequestPostProcessor user(Actor actor) {
        return opaqueToken().principal(new TokenPrincipal("test", Optional.of(actor.id()), SCOPES,
                Instant.now(), Instant.now().plusSeconds(3600), "test"));
    }

    private void settled() {
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL").query(Long.class).single() == 0);
    }
}
