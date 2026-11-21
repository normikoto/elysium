package dev.biribiri.elysium;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.graph.FollowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.opaqueToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.instance.base-url=https://endorsements.example")
@AutoConfigureMockMvc
class EndorsementsIntegrationTests {
    private static final Set<String> SCOPES = Set.of("read:accounts", "write:accounts", "read:follows",
            "write:follows", "write:blocks");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ActorService actors;
    @Autowired
    private FollowService follows;
    @Autowired
    private JdbcClient jdbc;
    private Actor alice;
    private Actor bob;
    private Actor carol;

    @BeforeEach
    void setUp() {
        settled();
        jdbc.sql("DELETE FROM follow").update();
        jdbc.sql("DELETE FROM account_block").update();
        jdbc.sql("UPDATE actor SET suspended_at = NULL").update();
        alice = local("enalice");
        bob = local("enbob");
        carol = local("encarol");
    }

    @Test
    void endorsesAnAccountTheUserFollows() throws Exception {
        follow(alice, bob);

        endorse(alice, bob, "endorse").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bob.id().toString()))
                .andExpect(jsonPath("$.endorsed").value(true));
        endorse(alice, bob, "endorse").andExpect(status().isOk()).andExpect(jsonPath("$.endorsed").value(true));

        mvc.perform(get("/api/v1/accounts/relationships").param("id[]", bob.id().toString()).with(user(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].endorsed").value(true));
        mvc.perform(get("/api/v1/endorsements").with(user(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(bob.id().toString()));
        mvc.perform(get("/api/v1/endorsements").with(user(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        assertThat(endorsementCount()).isOne();
    }

    @Test
    void theOldAndNewNamesAreTheSame() throws Exception {
        follow(alice, bob);

        endorse(alice, bob, "pin").andExpect(status().isOk()).andExpect(jsonPath("$.endorsed").value(true));
        endorse(alice, bob, "unendorse").andExpect(status().isOk()).andExpect(jsonPath("$.endorsed").value(false));
        endorse(alice, bob, "endorse").andExpect(status().isOk());
        endorse(alice, bob, "unpin").andExpect(status().isOk()).andExpect(jsonPath("$.endorsed").value(false));

        assertThat(endorsementCount()).isZero();
    }

    @Test
    void needsAnAcceptedFollow() throws Exception {
        endorse(alice, bob, "endorse").andExpect(status().isUnprocessableEntity());
        endorse(alice, alice, "pin").andExpect(status().isUnprocessableEntity());
        follow(bob, alice);
        endorse(alice, bob, "endorse").andExpect(status().isUnprocessableEntity());

        var remote = remote();
        follow(alice, remote);
        mvc.perform(get("/api/v1/accounts/relationships").param("id[]", remote.id().toString()).with(user(alice)))
                .andExpect(jsonPath("$[0].requested").value(true));
        endorse(alice, remote, "endorse").andExpect(status().isUnprocessableEntity());

        assertThat(endorsementCount()).isZero();
    }

    @Test
    void refusesSuspendedAndUnknownAccounts() throws Exception {
        follow(alice, bob);
        suspend(bob);

        endorse(alice, bob, "endorse").andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/accounts/" + UUID.randomUUID() + "/endorse").with(user(alice)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/accounts/not-an-id/pin").with(user(alice)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/accounts/" + UUID.randomUUID() + "/unendorse").with(user(alice)))
                .andExpect(status().isNotFound());
    }

    @Test
    void unendorsesASuspendedAccountAndLeavesItOutOfTheList() throws Exception {
        follow(alice, bob);
        follow(alice, carol);
        endorse(alice, bob, "endorse").andExpect(status().isOk());
        endorse(alice, carol, "endorse").andExpect(status().isOk());
        suspend(bob);

        mvc.perform(get("/api/v1/endorsements").with(user(alice)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(carol.id().toString()));
        endorse(alice, bob, "unendorse").andExpect(status().isOk()).andExpect(jsonPath("$.endorsed").value(false));
        assertThat(endorsementCount()).isOne();
    }

    @Test
    void endsWithTheFollow() throws Exception {
        follow(alice, bob);
        follow(alice, carol);
        endorse(alice, bob, "endorse").andExpect(status().isOk());
        endorse(alice, carol, "endorse").andExpect(status().isOk());

        mvc.perform(post("/api/v1/accounts/" + bob.id() + "/unfollow").with(user(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endorsed").value(false));
        mvc.perform(post("/api/v1/accounts/" + alice.id() + "/block").with(user(carol)))
                .andExpect(status().isOk());
        settled();

        assertThat(endorsementCount()).isZero();
        follow(alice, bob);
        mvc.perform(get("/api/v1/accounts/relationships").param("id[]", bob.id().toString()).with(user(alice)))
                .andExpect(jsonPath("$[0].following").value(true))
                .andExpect(jsonPath("$[0].endorsed").value(false));
    }

    @Test
    void pagesNewestFirst() throws Exception {
        var dave = local("endave");
        for (var target : new Actor[]{bob, carol, dave}) {
            follow(alice, target);
            endorse(alice, target, "endorse").andExpect(status().isOk());
        }

        var response = mvc.perform(get("/api/v1/endorsements").param("limit", "2").with(user(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(dave.id().toString()))
                .andExpect(jsonPath("$[1].id").value(carol.id().toString()))
                .andExpect(header().string("Link", containsString("rel=\"next\"")))
                .andReturn().getResponse();
        var next = response.getHeader("Link")
                .replaceAll(".*<https://endorsements.example([^>]*)>; rel=\"next\".*", "$1");
        var prev = response.getHeader("Link")
                .replaceAll(".*<https://endorsements.example([^>]*)>; rel=\"prev\".*", "$1");

        mvc.perform(get(next).with(user(alice)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(bob.id().toString()));
        mvc.perform(get(prev).with(user(alice)))
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/endorsements").param("max_id", "nope").with(user(alice)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void needsScopesAndAUser() throws Exception {
        follow(alice, bob);

        mvc.perform(post("/api/v1/accounts/" + bob.id() + "/endorse").with(token(alice.id(), Set.of("read"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/accounts/" + bob.id() + "/pin").with(token(alice.id(), Set.of("read"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/endorsements").with(token(alice.id(), Set.of("write"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/accounts/" + bob.id() + "/endorse").with(token(null, SCOPES)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/endorsements").with(token(null, SCOPES)))
                .andExpect(status().isUnprocessableEntity());
    }

    private ResultActions endorse(Actor actor, Actor target, String action) throws Exception {
        return mvc.perform(post("/api/v1/accounts/" + target.id() + "/" + action).with(user(actor)));
    }

    private void follow(Actor follower, Actor followed) {
        follows.follow(follower, followed);
        settled();
    }

    private void suspend(Actor actor) {
        jdbc.sql("UPDATE actor SET suspended_at = now() WHERE id = :id").param("id", actor.id()).update();
    }

    private long endorsementCount() {
        return jdbc.sql("SELECT count(*) FROM account_endorsement").query(Long.class).single();
    }

    private Actor local(String name) {
        return actors.findLocalByUsername(name).orElseGet(() -> actors.createLocalIfAbsent(name, actor -> {}).orElseThrow());
    }

    private Actor remote() {
        var domain = "remote.endorsements.example";
        var uri = URI.create("https://" + domain + "/users/" + UUID.randomUUID());
        return actors.upsertRemote(new RemoteActorProfile(uri, "remote", domain, URI.create(uri + "/inbox"), null, null, null));
    }

    private static RequestPostProcessor user(Actor actor) {
        return token(actor.id(), SCOPES);
    }

    private static RequestPostProcessor token(UUID actor, Set<String> scopes) {
        return opaqueToken().principal(new TokenPrincipal("test", Optional.ofNullable(actor), scopes,
                Instant.now(), Instant.now().plusSeconds(3600)));
    }

    private void settled() {
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL").query(Long.class).single() == 0);
    }
}
