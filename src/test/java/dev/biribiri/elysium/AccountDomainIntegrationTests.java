package dev.biribiri.elysium;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A server whose users' addresses are on another domain than its own, which redirects WebFinger requests here.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://elysium.example",
        "elysium.instance.account-domain=accounts.example"
})
@AutoConfigureMockMvc
class AccountDomainIntegrationTests {

    private static final String ACCOUNT_DOMAIN = "accounts.example";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ActorService actors;

    @Autowired
    private StatusService statuses;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Flyway flyway;

    private Actor alice;

    @BeforeEach
    void setUp() {
        actors.createLocalIfAbsent("alice", actor -> {
        });
        alice = actors.findLocalByUsername("alice").orElseThrow();
    }

    @Test
    void storesLocalActorsWithTheAccountDomain() {
        assertThat(alice.domain()).isEqualTo(ACCOUNT_DOMAIN);
        assertThat(alice.uri()).hasToString("https://elysium.example/users/alice");
        assertThat(actors.isLocal(alice)).isTrue();
        assertThat(actors.instanceActor().domain()).isEqualTo(ACCOUNT_DOMAIN);
    }

    @Test
    void webFingerAnswersForBothDomainsWithTheAccountDomainAsSubject() throws Exception {
        for (var resource : new String[]{"acct:alice@accounts.example", "acct:alice@elysium.example",
                "https://elysium.example/users/alice"}) {
            mockMvc.perform(get("/.well-known/webfinger").param("resource", resource))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.subject").value("acct:alice@accounts.example"))
                    .andExpect(jsonPath("$.links[0].rel").value("self"))
                    .andExpect(jsonPath("$.links[0].href").value("https://elysium.example/users/alice"));
        }
        mockMvc.perform(get("/.well-known/webfinger").param("resource", "acct:instance.actor@accounts.example"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("acct:instance.actor@accounts.example"))
                .andExpect(jsonPath("$.links[0].href").value("https://elysium.example/actor"));
        mockMvc.perform(get("/.well-known/webfinger").param("resource", "acct:alice@other.example"))
                .andExpect(status().isNotFound());
    }

    @Test
    void looksUpLocalAccountsOnEitherDomain() throws Exception {
        for (var acct : new String[]{"alice@accounts.example", "alice@elysium.example", "alice"}) {
            mockMvc.perform(get("/api/v1/accounts/lookup").param("acct", acct))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(alice.id().toString()))
                    .andExpect(jsonPath("$.acct").value("alice"));
        }
    }

    @Test
    void mentionsOnEitherDomainAreLocal() {
        actors.createLocalIfAbsent("bob", actor -> {
        });
        var bob = actors.findLocalByUsername("bob").orElseThrow();
        var status = statuses.create(alice.id(), new NewStatus(
                "@bob@accounts.example and @bob@elysium.example", null, null, Visibility.PUBLIC, false, null));

        assertThat(statuses.findAllMentioned(status.id())).containsExactly(bob.id());
    }

    @Test
    void instanceIsNamedForTheAccountDomain() throws Exception {
        mockMvc.perform(get("/api/v2/instance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value(ACCOUNT_DOMAIN));
        mockMvc.perform(get("/api/v1/instance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uri").value(ACCOUNT_DOMAIN));
    }

    @Test
    void migratingMovesLocalActorsToTheAccountDomain() {
        jdbc.sql("UPDATE actor SET domain = 'elysium.example' WHERE id = :id").param("id", alice.id()).update();
        assertThat(actors.findLocalByUsername("alice")).isEmpty();

        flyway.migrate();

        assertThat(actors.findLocalByUsername("alice")).map(Actor::id).contains(alice.id());
    }

    @Test
    void refusesAnAccountDomainThatRemoteActorsAreStoredWith() {
        actors.createLocalIfAbsent("stranger", actor -> {
        });
        var stranger = actors.findLocalByUsername("stranger").orElseThrow();
        jdbc.sql("UPDATE actor SET is_local = FALSE WHERE id = :id").param("id", stranger.id()).update();
        try {
            assertThatThrownBy(() -> flyway.migrate()).hasStackTraceContaining("taken for local accounts");
        } finally {
            jdbc.sql("UPDATE actor SET is_local = TRUE WHERE id = :id").param("id", stranger.id()).update();
        }
    }
}
