package dev.biribiri.elysium;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admins removing statuses. That a removed local status's {@code Delete} is sent is tested in
 * {@code StatusDeliveryIntegrationTests}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://removals.example",
        "elysium.jobs.worker-enabled=false"
})
@AutoConfigureMockMvc
class StatusRemovalIntegrationTests {

    private static final String PAGE = "/admin/statuses";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String ADMIN = "removalsadmin";
    private static final String USER = "removalsuser";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;
    @Autowired
    private StatusService statuses;

    private Actor admin;
    private Actor user;

    @BeforeEach
    void setUp() throws Exception {
        TestAccounts.makeSureAnAccountExists(actors);
        ensureAccount(ADMIN);
        ensureAccount(USER);
        admin = actors.findLocalByUsername(ADMIN).orElseThrow();
        user = actors.findLocalByUsername(USER).orElseThrow();
        TestAccounts.makeAdmin(jdbc, admin.id());
    }

    @Test
    void onlyAdminsCanUseThePage() throws Exception {
        var status = publish(Visibility.PUBLIC, "Mine");
        var session = logIn(USER);

        mockMvc.perform(get(PAGE).session(session)).andExpect(status().isForbidden());
        mockMvc.perform(post(PAGE + "/" + status.id() + "/remove").session(session).with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(statuses.findVisible(status.id(), Optional.empty())).isPresent();
    }

    @Test
    void findsALocalStatusByItsIdOrUrl() throws Exception {
        var status = publish(Visibility.PUBLIC, "Found by its URL");
        var session = logIn(ADMIN);

        for (var query : new String[]{status.id().toString(), status.uri().toString(), " " + status.uri() + " "}) {
            mockMvc.perform(get(PAGE).session(session).param("q", query))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Found by its URL")))
                    .andExpect(content().string(containsString(USER)));
        }
    }

    @Test
    void findsARemoteStatusByItsUriOrPage() throws Exception {
        var author = remoteAuthor();
        var status = remoteStatus(author, Visibility.PUBLIC);
        var session = logIn(ADMIN);

        for (var query : new String[]{status.uri().toString(), status.url().toString()}) {
            mockMvc.perform(get(PAGE).session(session).param("q", query))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("From afar")))
                    .andExpect(content().string(containsString("r@" + author.domain())));
        }
    }

    @Test
    void saysWhenNothingIsFound() throws Exception {
        mockMvc.perform(get(PAGE).session(logIn(ADMIN)).param("q", "https://nowhere.example/notes/1"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("No status was found")));
    }

    @Test
    void doesntShowWhatOnlyFollowersOrMentionedAccountsCanSee() throws Exception {
        var session = logIn(ADMIN);
        for (var visibility : new Visibility[]{Visibility.PRIVATE, Visibility.DIRECT}) {
            var status = publish(visibility, "Secret " + visibility);

            mockMvc.perform(get(PAGE).session(session).param("q", status.id().toString()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("Secret"))))
                    .andExpect(content().string(containsString("isn't public")));
        }
    }

    @Test
    void removesALocalStatusAndRemembersWhoDid() throws Exception {
        var status = publish(Visibility.PUBLIC, "Against the rules");

        mockMvc.perform(post(PAGE + "/" + status.id() + "/remove").session(logIn(ADMIN)).with(csrf()))
                .andExpect(redirectedUrl(PAGE + "?removed"));

        assertThat(jdbc.sql("SELECT count(*) FROM status WHERE id = :id").param("id", status.id())
                .query(Long.class).single()).isZero();
        assertThat(removedBy(status)).isEqualTo(admin.id());
        mockMvc.perform(get(status.uri().getPath()).header(HttpHeaders.ACCEPT, "application/activity+json"))
                .andExpect(status().isGone());
    }

    @Test
    void removesARemoteStatusForGood() {
        var author = remoteAuthor();
        var status = remoteStatus(author, Visibility.PUBLIC);

        assertThat(statuses.remove(status.id(), admin.id())).isPresent();

        assertThat(removedBy(status)).isEqualTo(admin.id());
        // What its server's Delete would have left behind is purged with old jobs, unlike the removal.
        jdbc.sql("DELETE FROM deleted_remote_status WHERE uri = :uri").param("uri", status.uri().toString()).update();
        assertThat(statuses.storeRemote(remote(status.uri(), status.url(), author, Visibility.PUBLIC))).isEmpty();
        assertThat(statuses.findForModeration(status.uri().toString())).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM outbound_activity WHERE activity_id LIKE :uri")
                .param("uri", status.uri() + "%").query(Long.class).single()).isZero();
    }

    @Test
    void removingWhatsAlreadyGoneDoesNothing() throws Exception {
        var status = publish(Visibility.PUBLIC, "Deleted first");
        statuses.delete(status.id(), user.id());

        mockMvc.perform(post(PAGE + "/" + status.id() + "/remove").session(logIn(ADMIN)).with(csrf()))
                .andExpect(redirectedUrl(PAGE + "?removed"));

        assertThat(statuses.remove(status.id(), admin.id())).isEmpty();
        assertThat(removals(status)).isZero();
    }

    @Test
    void removalsGoWithTheAuthorsAccountOrServer() {
        var author = remoteAuthor();
        var byAccount = remoteStatus(author, Visibility.PUBLIC);
        var byDomain = remoteStatus(remoteAuthor(), Visibility.PUBLIC);
        statuses.remove(byAccount.id(), admin.id());
        statuses.remove(byDomain.id(), admin.id());

        statuses.removeAllBy(author.id());
        statuses.removeForDomain(actors.findById(byDomain.actorId()).orElseThrow().domain());

        assertThat(removals(byAccount)).isZero();
        assertThat(removals(byDomain)).isZero();
    }

    private Status publish(Visibility visibility, String text) {
        return statuses.create(user.id(), new NewStatus(text, null, null, visibility, false, null));
    }

    private Actor remoteAuthor() {
        var host = "removals-" + UUID.randomUUID() + ".example";
        var uri = URI.create("https://" + host + "/users/r");
        return actors.upsertRemote(
                new RemoteActorProfile(uri, "r", host, URI.create(uri + "/inbox"), null, null, null, null));
    }

    private Status remoteStatus(Actor author, Visibility visibility) {
        var id = UUID.randomUUID();
        var stored = statuses.storeRemote(remote(
                URI.create(author.uri() + "/notes/" + id), URI.create("https://" + author.domain() + "/@r/" + id), author,
                visibility)).orElseThrow();
        return statuses.findForModeration(stored.toString()).orElseThrow();
    }

    private static RemoteStatus remote(URI uri, URI url, Actor author, Visibility visibility) {
        return new RemoteStatus(uri, url, author.id(), "<p>From afar</p>", null, null, visibility, false, null,
                Instant.now());
    }

    private UUID removedBy(Status status) {
        return jdbc.sql("SELECT removed_by FROM status_removal WHERE status_uri = :uri")
                .param("uri", status.uri().toString())
                .query(UUID.class)
                .single();
    }

    private long removals(Status status) {
        return jdbc.sql("SELECT count(*) FROM status_removal WHERE status_uri = :uri")
                .param("uri", status.uri().toString())
                .query(Long.class)
                .single();
    }

    private void ensureAccount(String username) throws Exception {
        if (actors.findLocalByUsername(username).isEmpty()) {
            mockMvc.perform(post("/signup").with(csrf())
                            .param("invite", TestAccounts.invite(jdbc))
                            .param("username", username)
                            .param("password", PASSWORD)
                            .param("confirmPassword", PASSWORD))
                    .andExpect(status().is3xxRedirection());
        }
    }

    private MockHttpSession logIn(String username) throws Exception {
        return TestOAuth.logIn(mockMvc, username, PASSWORD);
    }
}
