package dev.biribiri.elysium.graph;

import dev.biribiri.elysium.TestAccounts;
import dev.biribiri.elysium.TestOAuth;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import dev.biribiri.elysium.actor.RemoteActorResolver.ResolveLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The job is off here, so that tests run imports themselves.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://relationship-files.example",
        "elysium.jobs.worker-enabled=false"})
@AutoConfigureMockMvc
class RelationshipFilesIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String IMPORTS = "/settings/imports";
    private static final String LOCAL = "relationship-files.example";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;
    @Autowired
    private FollowService follows;
    @Autowired
    private BlockService blocks;
    @Autowired
    private MuteService mutes;
    @Autowired
    private RelationshipFiles files;
    @Autowired
    private GraphRemoval removal;
    @MockitoBean
    private RemoteActorResolver resolver;

    @BeforeEach
    void setUp() {
        TestAccounts.makeSureAnAccountExists(actors);
    }

    @Test
    void exportsWhatAnAccountFollowsBlocksAndMutesAsMastodonDoes() throws Exception {
        var alice = signUp();
        var bob = account();
        var carol = account();
        var dave = account();
        var erin = account();
        follows.follow(alice.actor(), bob, FollowOptions.of(false, true, List.of("en", "de")));
        follows.follow(alice.actor(), carol);
        blocks.block(alice.actor(), dave);
        mutes.mute(alice.actor().id(), erin.id(), false, Optional.empty());
        mutes.mute(alice.actor().id(), carol.id(), true, Optional.of(Instant.now().minus(Duration.ofMinutes(1))));

        mockMvc.perform(get("/settings/exports/follows.csv").session(alice.session()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("following_accounts.csv")))
                .andExpect(content().string("""
                        Account address,Show boosts,Notify on new posts,Languages
                        %s,false,true,"en, de"
                        %s,true,false,
                        """.formatted(address(bob), address(carol))));
        mockMvc.perform(get("/settings/exports/blocks.csv").session(alice.session()))
                .andExpect(content().string(address(dave) + "\n"));
        // The expired mute of carol is left out.
        mockMvc.perform(get("/settings/exports/mutes.csv").session(alice.session()))
                .andExpect(content().string("Account address,Hide notifications\n" + address(erin) + ",false\n"));
    }

    @Test
    void importsFollowsBlocksAndMutesFromMastodonsFiles() throws Exception {
        var alice = signUp();
        var bob = account();
        var carol = account();
        var dave = remote("dave");
        when(resolver.resolveAcct(any(), eq("dave"), eq("remote.example"))).thenReturn(Optional.of(dave));

        upload(alice, "follows", """
                Account address,Show boosts,Notify on new posts,Languages
                %s,false,true,"en, de"
                @dave@remote.example,true,false,
                %s,true,false,
                nobody@remote.example,true,false,
                """.formatted(address(bob), address(alice.actor())));
        runImport(alice.actor());

        assertThat(follows.options(alice.actor().id(), bob.id()))
                .contains(FollowOptions.of(false, true, List.of("en", "de")));
        assertThat(follows.options(alice.actor().id(), dave.id())).isPresent();
        // Itself, and an account that isn't found.
        assertThat(files.latest(alice.actor().id()).orElseThrow().failed()).isEqualTo(2);
        mockMvc.perform(get(IMPORTS).session(alice.session()))
                .andExpect(content().string(containsString("accounts were added")));

        upload(alice, "blocks", address(carol) + "\n");
        runImport(alice.actor());
        assertThat(relationship(alice.actor(), carol).blocking()).isTrue();

        upload(alice, "mutes", "Account address,Hide notifications\n" + carol.username() + ",false\n");
        runImport(alice.actor());
        assertThat(relationship(alice.actor(), carol).muting()).isTrue();
        assertThat(relationship(alice.actor(), carol).mutingNotifications()).isFalse();
    }

    @Test
    void anExportImportsIntoAnotherAccount() throws Exception {
        var alice = signUp();
        var bob = signUp();
        var carol = account();
        follows.follow(alice.actor(), carol, FollowOptions.of(true, true, List.of()));

        var exported = mockMvc.perform(get("/settings/exports/follows.csv").session(alice.session()))
                .andReturn().getResponse().getContentAsString();
        upload(bob, "follows", exported);
        runImport(bob.actor());

        assertThat(follows.options(bob.actor().id(), carol.id())).contains(FollowOptions.of(true, true, List.of()));
    }

    @Test
    void waitsWhenTheAccountHasLookedUpTooManyAccounts() throws Exception {
        var alice = signUp();
        when(resolver.resolveAcct(any(), eq("dave"), eq("remote.example"))).thenThrow(new ResolveLimitException());

        upload(alice, "blocks", "dave@remote.example\n");
        var importId = importId(alice.actor());
        files.run(new RelationshipImportJob(importId, Instant.now()));

        assertThat(files.latest(alice.actor().id()).orElseThrow().remaining()).isEqualTo(1);
        assertThat(files.latest(alice.actor().id()).orElseThrow().finishedAt()).isNull();
    }

    @Test
    void refusesFilesItCannotImport() throws Exception {
        var alice = signUp();

        mockMvc.perform(multipart(IMPORTS).file(file("not an address!\n")).param("kind", "follows")
                        .session(alice.session()).with(csrf()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("isn&#39;t an address")));
        mockMvc.perform(multipart(IMPORTS).file(file("Account address,Hide notifications\nbob@remote.example,maybe\n"))
                        .param("kind", "mutes").session(alice.session()).with(csrf()))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(multipart(IMPORTS).file(file("")).param("kind", "follows").session(alice.session()).with(csrf()))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(multipart(IMPORTS).file(file("bob@remote.example\n")).param("kind", "lists")
                        .session(alice.session()).with(csrf()))
                .andExpect(status().isUnprocessableContent());
        assertThat(files.latest(alice.actor().id())).isEmpty();

        upload(alice, "blocks", "bob@remote.example\n");
        mockMvc.perform(multipart(IMPORTS).file(file("carol@remote.example\n")).param("kind", "blocks")
                        .session(alice.session()).with(csrf()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("still running")));
    }

    @Test
    void thePagesNeedALogin() throws Exception {
        mockMvc.perform(get(IMPORTS)).andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/settings/exports/follows.csv")).andExpect(redirectedUrl("/login"));

        var alice = signUp();
        mockMvc.perform(multipart(IMPORTS).file(file("bob@remote.example\n")).param("kind", "blocks")
                        .session(alice.session()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/settings").session(alice.session()))
                .andExpect(content().string(containsString("href=\"" + IMPORTS + "\"")));
    }

    @Test
    void aDeletedAccountsImportsAreRemoved() throws Exception {
        var alice = signUp();
        upload(alice, "blocks", "bob@remote.example\n");

        removal.removeForDeleted(alice.actor().id());

        assertThat(files.latest(alice.actor().id())).isEmpty();
    }

    private void upload(Session session, String kind, String csv) throws Exception {
        mockMvc.perform(multipart(IMPORTS).file(file(csv)).param("kind", kind).session(session.session()).with(csrf()))
                .andExpect(redirectedUrl(IMPORTS + "?started"));
        var importId = importId(session.actor());
        assertThat(jdbc.sql("SELECT count(*) FROM scheduled_tasks WHERE task_name = 'relationship-import'")
                .query(Long.class).single()).isPositive();
        assertThat(importId).isNotNull();
    }

    /**
     * Runs the job as often as it takes, and more, which changes nothing.
     */
    private void runImport(Actor account) {
        var importId = importId(account);
        for (var i = 0; i < 5; i++) {
            files.run(new RelationshipImportJob(importId, Instant.now()));
        }
        assertThat(files.latest(account.id()).orElseThrow().finishedAt()).isNotNull();
    }

    private Relationship relationship(Actor account, Actor other) {
        return follows.relationships(account.id(), List.of(other.id())).getFirst();
    }

    private UUID importId(Actor account) {
        return files.latest(account.id()).orElseThrow().id();
    }

    private static MockMultipartFile file(String csv) {
        return new MockMultipartFile("file", "accounts.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    private static String address(Actor actor) {
        return actor.username() + "@" + actor.domain();
    }

    private Actor account() {
        return actors.createLocalIfAbsent(newUsername(), actor -> {
        }).orElseThrow();
    }

    private Actor remote(String username) {
        var uri = URI.create("https://remote.example/users/" + username + "-" + UUID.randomUUID());
        return actors.upsertRemote(new RemoteActorProfile(uri, username, "remote.example", URI.create(uri + "/inbox"),
                null, URI.create("https://remote.example/inbox"), null, null));
    }

    private Session signUp() throws Exception {
        var username = newUsername();
        mockMvc.perform(post("/signup").with(csrf())
                        .param("invite", TestAccounts.invite(jdbc))
                        .param("username", username)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(status().is3xxRedirection());
        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);
        return new Session(actors.findLocalByUsername(username).orElseThrow(), session);
    }

    private static String newUsername() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private record Session(Actor actor, MockHttpSession session) {
    }
}
