package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.auth.AccountActionRefusedException;
import dev.biribiri.elysium.auth.AccountActions;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Suspending and deleting accounts through the API and pages, and what everyone else then sees. What's sent to other servers,
 * and what's removed later, is tested in {@code AccountCleanupIntegrationTests} and {@code ActivityDeliveryIntegrationTests}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://moderation.example",
        "elysium.jobs.worker-enabled=false"
})
@AutoConfigureMockMvc
class AccountModerationIntegrationTests {

    private static final String PATH = "/api/v1/admin/accounts";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String ADMIN = "moderationadmin";
    private static final String ACTIVITY_JSON = "application/activity+json";

    /**
     * Logging in is slow because of Argon2, so each token is only created once.
     */
    private static final Map<String, String> TOKENS = new HashMap<>();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;
    @Autowired
    private AccountActions accounts;
    @Autowired
    private StatusService statuses;

    private Actor admin;

    @BeforeEach
    void setUp() throws Exception {
        TestAccounts.makeSureAnAccountExists(actors);
        if (actors.findLocalByUsername(ADMIN).isEmpty()) {
            signUp(ADMIN);
        }
        admin = actors.findLocalByUsername(ADMIN).orElseThrow();
        TestAccounts.makeAdmin(jdbc, admin.id());
    }

    // Listing

    @Test
    void listsAccountsWithTheirState() throws Exception {
        var user = newAccount();
        var remote = newRemote();
        accounts.suspend(remote.id(), Optional.empty());

        getAccount(user).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(user.username()))
                .andExpect(jsonPath("$.domain").value(nullValue()))
                .andExpect(jsonPath("$.role.name").value("User"))
                .andExpect(jsonPath("$.suspended").value(false))
                .andExpect(jsonPath("$.account.acct").value(user.username()));
        getAccount(remote).andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value(remote.domain()))
                .andExpect(jsonPath("$.role").value(nullValue()))
                .andExpect(jsonPath("$.suspended").value(true))
                .andExpect(jsonPath("$.account.acct").value(remote.username() + "@" + remote.domain()));
    }

    @Test
    void filtersTheList() throws Exception {
        var user = newAccount();
        var remote = newRemote();
        accounts.suspend(remote.id(), Optional.empty());

        list("local=true").andExpect(jsonPath("$[*].id", hasItem(user.id().toString())))
                .andExpect(jsonPath("$[*].id", not(hasItem(remote.id().toString()))));
        list("remote=true").andExpect(jsonPath("$[*].id", hasItem(remote.id().toString())))
                .andExpect(jsonPath("$[*].id", not(hasItem(user.id().toString()))));
        list("suspended=true").andExpect(jsonPath("$[*].id", hasItem(remote.id().toString())))
                .andExpect(jsonPath("$[*].id", not(hasItem(user.id().toString()))));
        list("username=" + user.username().substring(0, 20).toUpperCase())
                .andExpect(jsonPath("$[*].id", hasItem(user.id().toString())));
        list("by_domain=" + remote.domain()).andExpect(jsonPath("$[*].id", hasItem(remote.id().toString())))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void underscoreInAUsernameSearchIsNotAWildcard() throws Exception {
        var user = newAccount();

        list("username=_" + user.username().substring(1))
                .andExpect(jsonPath("$[*].id", not(hasItem(user.id().toString()))));
    }

    @Test
    void pagesThroughTheList() throws Exception {
        newAccount();
        newAccount();

        var first = list("limit=1").andExpect(header().string("Link", containsString("rel=\"next\"")))
                .andReturn().getResponse().getContentAsString();
        String firstId = JsonPath.read(first, "$[0].id");

        list("limit=1&max_id=" + firstId).andExpect(jsonPath("$[0].id", not(equalTo(firstId))));
    }

    @Test
    void doesNotListDeletedAccounts() throws Exception {
        var user = newAccount();
        accounts.delete(user.id(), Optional.empty());

        list("local=true").andExpect(jsonPath("$[*].id", not(hasItem(user.id().toString()))));
        getAccount(user).andExpect(status().isNotFound());
    }

    // Suspending

    @Test
    void suspendingRefusesTokensAndUnsuspendingRestoresThem() throws Exception {
        var user = newAccount();
        var token = TestOAuth.userToken(mockMvc, user.username(), PASSWORD, "read");
        verifyCredentials(token).andExpect(status().isOk());

        mockMvc.perform(withToken(post(PATH + "/" + user.id() + "/action"), write())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("type", "suspend"))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));
        verifyCredentials(token).andExpect(status().isUnauthorized());

        mockMvc.perform(withToken(post(PATH + "/" + user.id() + "/unsuspend"), write()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspended").value(false));
        verifyCredentials(token).andExpect(status().isOk());
    }

    @Test
    void takesTheActionAsJson() throws Exception {
        var user = newAccount();

        mockMvc.perform(withToken(post(PATH + "/" + user.id() + "/action"), write())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"suspend\"}"))
                .andExpect(status().isOk());

        assertThat(actors.findById(user.id()).orElseThrow().isSuspended()).isTrue();
    }

    @Test
    void refusesOtherActions() throws Exception {
        var user = newAccount();

        for (var type : List.of("silence", "disable", "none")) {
            mockMvc.perform(withToken(post(PATH + "/" + user.id() + "/action"), write())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("type", type))
                    .andExpect(status().isUnprocessableContent());
        }

        assertThat(actors.findById(user.id()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void suspendingTwiceChangesNothing() {
        var user = newAccount();

        assertThat(accounts.suspend(user.id(), Optional.empty())).isTrue();
        var suspendedAt = actors.findById(user.id()).orElseThrow().suspendedAt();
        assertThat(accounts.suspend(user.id(), Optional.empty())).isTrue();

        assertThat(actors.findById(user.id()).orElseThrow().suspendedAt()).isEqualTo(suspendedAt);
    }

    @Test
    void unsuspendingAnAccountThatIsNotSuspendedIsRefused() throws Exception {
        var user = newAccount();

        mockMvc.perform(withToken(post(PATH + "/" + user.id() + "/unsuspend"), write()))
                .andExpect(status().isForbidden());
    }

    @Test
    void refusesLoginsWhileSuspended() throws Exception {
        var user = newAccount();
        accounts.suspend(user.id(), Optional.empty());

        logIn(user.username()).andExpect(redirectedUrl("/login?error"));

        accounts.unsuspend(user.id());
        logIn(user.username()).andExpect(redirectedUrl("/"));
    }

    @Test
    void endsLoginSessionsOfASuspendedAccountEvenAnAdmins() throws Exception {
        var user = newAccount();
        TestAccounts.makeAdmin(jdbc, user.id());
        var session = TestOAuth.logIn(mockMvc, user.username(), PASSWORD);
        // Its own session, because the first request after the suspension ends the other one.
        var adminSession = TestOAuth.logIn(mockMvc, user.username(), PASSWORD);
        mockMvc.perform(get("/settings/delete-account").session(session)).andExpect(status().isOk());
        mockMvc.perform(get("/admin/accounts").session(adminSession)).andExpect(status().isOk());

        // As a command would, in a process that can't reach sessions.
        accounts.suspend(user.id(), Optional.empty());

        mockMvc.perform(get("/settings/delete-account").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/admin/accounts").session(adminSession).accept(MediaType.TEXT_HTML))
                .andExpect(redirectedUrl("/login"));
    }

    // Who can do what

    @Test
    void anAdminCannotSuspendOrDeleteThemselves() throws Exception {
        newAccountAsAdmin();

        mockMvc.perform(withToken(post(PATH + "/" + admin.id() + "/action"), write())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("type", "suspend"))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(delete(PATH + "/" + admin.id()), write())).andExpect(status().isForbidden());

        assertThat(actors.findById(admin.id()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void theLastAdminCannotBeSuspendedOrDeletedEvenByACommand() {
        onlyAdmin(admin);

        assertThatThrownBy(() -> accounts.suspend(admin.id(), Optional.empty()))
                .isInstanceOf(AccountActionRefusedException.class);
        assertThatThrownBy(() -> accounts.delete(admin.id(), Optional.empty()))
                .isInstanceOf(AccountActionRefusedException.class);

        assertThat(actors.findById(admin.id()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void aSuspendedAdminDoesNotCountAsAnotherAdmin() {
        var other = newAccountAsAdmin();
        onlyAdmin(admin, other);
        accounts.suspend(other.id(), Optional.empty());

        assertThatThrownBy(() -> accounts.delete(admin.id(), Optional.empty()))
                .isInstanceOf(AccountActionRefusedException.class);
    }

    @Test
    void aSuspendedAdminCanBeDeletedWhileAnotherAdminRemains() {
        var other = newAccountAsAdmin();
        onlyAdmin(admin, other);
        accounts.suspend(other.id(), Optional.empty());

        assertThat(accounts.delete(other.id(), Optional.empty())).isTrue();
    }

    @Test
    void twoAdminsCannotRemoveEachOtherAtOnce() throws Exception {
        var other = newAccountAsAdmin();
        onlyAdmin(admin, other);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var attempts = executor.invokeAll(List.<Callable<Boolean>>of(
                    () -> attempt(other.id(), admin.id()),
                    () -> attempt(admin.id(), other.id())));
            var succeeded = 0;
            for (var attempt : attempts) {
                succeeded += attempt.get() ? 1 : 0;
            }
            assertThat(succeeded).isEqualTo(1);
        } finally {
            // The other tests need this admin, and shouldn't find either account suspended.
            actors.unsuspend(admin.id());
            actors.unsuspend(other.id());
        }
    }

    private boolean attempt(UUID target, UUID actingAdmin) {
        try {
            return accounts.suspend(target, Optional.of(actingAdmin));
        } catch (AccountActionRefusedException e) {
            return false;
        }
    }

    @Test
    void aRegularUserCannotUseTheAdminApi() throws Exception {
        var user = newAccount();
        var victim = newAccount();
        var token = TestOAuth.userToken(mockMvc, user.username(), PASSWORD, "admin:read:accounts admin:write:accounts read");

        mockMvc.perform(withToken(get(PATH), token)).andExpect(status().isForbidden());
        mockMvc.perform(withToken(get(PATH + "/" + victim.id()), token)).andExpect(status().isForbidden());
        mockMvc.perform(withToken(post(PATH + "/" + victim.id() + "/action"), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("type", "suspend"))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(post(PATH + "/" + victim.id() + "/unsuspend"), token)).andExpect(status().isForbidden());
        mockMvc.perform(withToken(delete(PATH + "/" + victim.id()), token)).andExpect(status().isForbidden());

        assertThat(actors.findById(victim.id()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void aRemoteAccountCannotBeDeleted() throws Exception {
        var remote = newRemote();

        mockMvc.perform(withToken(delete(PATH + "/" + remote.id()), write())).andExpect(status().isForbidden());

        assertThat(actors.findById(remote.id()).orElseThrow().isDeleted()).isFalse();
    }

    // What others see

    @Test
    void aSuspendedAccountIsHiddenAndSaysSoInADifferentWayToADeletedOne() throws Exception {
        var user = newAccount();
        var status = publish(user);
        var before = actors.countLocal();
        var path = "/users/" + user.username();

        accounts.suspend(user.id(), Optional.empty());

        mockMvc.perform(get(path).accept(ACTIVITY_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspended").value(true))
                .andExpect(jsonPath("$.publicKey.publicKeyPem").isString())
                .andExpect(jsonPath("$.endpoints.sharedInbox").value("https://moderation.example/inbox"))
                .andExpect(jsonPath("$['@context'][2].suspended").value("toot:suspended"));
        mockMvc.perform(get(path + "/statuses/" + status.id()).accept(ACTIVITY_JSON)).andExpect(status().isForbidden());
        mockMvc.perform(get(path + "/followers").accept(ACTIVITY_JSON)).andExpect(status().isForbidden());
        mockMvc.perform(get(path + "/outbox").accept(ACTIVITY_JSON)).andExpect(status().isForbidden());
        mockMvc.perform(get("/.well-known/webfinger").param("resource", "acct:" + user.username() + "@moderation.example"))
                .andExpect(status().isOk());
        mockMvc.perform(get(path).accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound());
        mockMvc.perform(get(path + "/statuses/" + status.id()).accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/statuses/" + status.id())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/accounts/" + user.id() + "/statuses")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/timelines/public"))
                .andExpect(jsonPath("$[*].id", not(hasItem(status.id().toString()))));
        assertThat(actors.countLocal()).isEqualTo(before - 1);

        accounts.unsuspend(user.id());

        mockMvc.perform(get(path).accept(ACTIVITY_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspended").doesNotExist());
        mockMvc.perform(get(path + "/statuses/" + status.id()).accept(ACTIVITY_JSON)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/statuses/" + status.id())).andExpect(status().isOk());
        assertThat(actors.countLocal()).isEqualTo(before);
    }

    @Test
    void aFullPageOfASuspendedAccountsStatusesDoesNotLeaveTheTimelineShort() throws Exception {
        var shown = newAccount();
        var hidden = newAccount();
        var kept = publish(shown);
        publish(hidden);
        publish(hidden);
        accounts.suspend(hidden.id(), Optional.empty());

        mockMvc.perform(get("/api/v1/timelines/public").param("limit", "1"))
                .andExpect(jsonPath("$[0].id").value(kept.id().toString()));
    }

    @Test
    void aSuspendedAccountsStatusCannotBeRepliedToAndItsRepliesAreNotCounted() {
        var user = newAccount();
        var replier = newAccount();
        var parent = publish(user);
        statuses.create(replier.id(), new NewStatus("A reply", "", null, Visibility.PUBLIC, false, parent.id()));
        assertThat(statuses.findVisible(parent.id(), Optional.empty()).orElseThrow().repliesCount()).isEqualTo(1);

        accounts.suspend(replier.id(), Optional.empty());
        assertThat(statuses.findVisible(parent.id(), Optional.empty()).orElseThrow().repliesCount()).isZero();

        accounts.suspend(user.id(), Optional.empty());
        assertThat(statuses.findVisible(parent.id(), Optional.empty())).isEmpty();
    }

    @Test
    void aDeletedAccountAnswersGone() throws Exception {
        var user = newAccount();
        var status = publish(user);
        var path = "/users/" + user.username();

        accounts.delete(user.id(), Optional.empty());

        mockMvc.perform(get(path).accept(ACTIVITY_JSON))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("Tombstone"))
                .andExpect(jsonPath("$.formerType").value("Person"))
                .andExpect(jsonPath("$.id").value(user.uri().toString()));
        mockMvc.perform(get(path + "/statuses/" + status.id()).accept(ACTIVITY_JSON))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.formerType").value("Note"));
        mockMvc.perform(get(path + "/followers").accept(ACTIVITY_JSON)).andExpect(status().isGone());
        mockMvc.perform(get(path + "/outbox").accept(ACTIVITY_JSON)).andExpect(status().isGone());
        mockMvc.perform(get("/.well-known/webfinger").param("resource", "acct:" + user.username() + "@moderation.example"))
                .andExpect(status().isGone());
        mockMvc.perform(get("/.well-known/webfinger").param("resource", user.uri().toString()))
                .andExpect(status().isGone());
        mockMvc.perform(post(path + "/inbox").contentType(ACTIVITY_JSON).content("{}")).andExpect(status().isGone());
        mockMvc.perform(get(path).accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound());
    }

    @Test
    void aDeletedAccountsUsernameCannotBeUsedAgain() throws Exception {
        var user = newAccount();
        accounts.delete(user.id(), Optional.empty());

        mockMvc.perform(post("/signup").with(csrf())
                        .param("invite", TestAccounts.invite(jdbc))
                        .param("username", user.username())
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(status().isUnprocessableContent());

        assertThat(actors.findByUri(user.uri()).orElseThrow().id()).isEqualTo(user.id());
    }

    @Test
    void deletingRemovesCredentialsAndTokens() throws Exception {
        var user = newAccount();
        var token = TestOAuth.userToken(mockMvc, user.username(), PASSWORD, "read");

        mockMvc.perform(withToken(delete(PATH + "/" + user.id()), write()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspended").value(true));

        verifyCredentials(token).andExpect(status().isUnauthorized());
        logIn(user.username()).andExpect(redirectedUrl("/login?error"));
        assertThat(count("SELECT count(*) FROM account_credential WHERE actor_id = :id", user.id())).isZero();
        assertThat(count("SELECT count(*) FROM oauth2_authorization WHERE principal_name = :id::text", user.id())).isZero();
        assertThat(actors.findById(user.id())).isPresent();
    }

    // Deleting your own account

    @Test
    void theDeletePageNeedsALogin() throws Exception {
        mockMvc.perform(get("/settings/delete-account")).andExpect(redirectedUrl("/login"));
        mockMvc.perform(post("/settings/delete-account").with(csrf()).param("password", PASSWORD))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletingYourOwnAccountNeedsTheRightPassword() throws Exception {
        var user = newAccount();
        var session = TestOAuth.logIn(mockMvc, user.username(), PASSWORD);

        mockMvc.perform(post("/settings/delete-account").session(session).with(csrf()).param("password", "wrong password"))
                .andExpect(status().isUnprocessableContent());
        assertThat(actors.findById(user.id()).orElseThrow().isDeleted()).isFalse();

        mockMvc.perform(post("/settings/delete-account").session(session).with(csrf()).param("password", PASSWORD))
                .andExpect(redirectedUrl("/login?deleted"));
        assertThat(actors.findById(user.id()).orElseThrow().isDeleted()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM spring_session WHERE principal_name = :id")
                .param("id", user.id().toString())
                .query(Long.class)
                .single()).isZero();
        mockMvc.perform(get("/settings/delete-account").session(session).accept(MediaType.TEXT_HTML))
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void deletingYourOwnAccountNeedsACsrfToken() throws Exception {
        var user = newAccount();
        var session = TestOAuth.logIn(mockMvc, user.username(), PASSWORD);

        mockMvc.perform(post("/settings/delete-account").session(session).param("password", PASSWORD))
                .andExpect(status().isForbidden());

        assertThat(actors.findById(user.id()).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    void theLastAdminCannotDeleteTheirOwnAccount() throws Exception {
        onlyAdmin(admin);
        var session = TestOAuth.logIn(mockMvc, ADMIN, PASSWORD);

        mockMvc.perform(post("/settings/delete-account").session(session).with(csrf()).param("password", PASSWORD))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("last admin")));

        assertThat(actors.findById(admin.id()).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    void anAdminCanDeleteTheirOwnAccountWhileAnotherAdminRemains() throws Exception {
        var other = newAccountAsAdmin();
        var session = TestOAuth.logIn(mockMvc, other.username(), PASSWORD);

        mockMvc.perform(post("/settings/delete-account").session(session).with(csrf()).param("password", PASSWORD))
                .andExpect(redirectedUrl("/login?deleted"));
    }

    // The admin page

    @Test
    void theAdminPageIsOnlyForAdmins() throws Exception {
        var user = newAccount();
        var session = TestOAuth.logIn(mockMvc, user.username(), PASSWORD);

        mockMvc.perform(get("/admin/accounts").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/accounts/" + admin.id() + "/suspend").session(session).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminCanSuspendUnsuspendAndDeleteFromThePage() throws Exception {
        var user = newAccount();
        var session = TestOAuth.logIn(mockMvc, ADMIN, PASSWORD);
        var base = "/admin/accounts/" + user.id();

        mockMvc.perform(get("/admin/accounts").session(session).param("username", user.username()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(user.username())));
        mockMvc.perform(post(base + "/suspend").session(session).with(csrf())).andExpect(redirectedUrl("/admin/accounts"));
        assertThat(actors.findById(user.id()).orElseThrow().isSuspended()).isTrue();
        mockMvc.perform(post(base + "/unsuspend").session(session).with(csrf())).andExpect(redirectedUrl("/admin/accounts"));
        assertThat(actors.findById(user.id()).orElseThrow().isSuspended()).isFalse();

        mockMvc.perform(get(base + "/delete").session(session)).andExpect(status().isOk());
        mockMvc.perform(post(base + "/delete").session(session).with(csrf()).param("username", "someone else"))
                .andExpect(status().isUnprocessableContent());
        assertThat(actors.findById(user.id()).orElseThrow().isDeleted()).isFalse();
        mockMvc.perform(post(base + "/delete").session(session).with(csrf()).param("username", user.username()))
                .andExpect(redirectedUrl("/admin/accounts"));
        assertThat(actors.findById(user.id()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    void thePageShowsWhyAnActionWasRefused() throws Exception {
        var session = TestOAuth.logIn(mockMvc, ADMIN, PASSWORD);

        mockMvc.perform(post("/admin/accounts/" + admin.id() + "/suspend").session(session).with(csrf()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("your own account")));
    }

    private ResultActions getAccount(Actor account) throws Exception {
        return mockMvc.perform(withToken(get(PATH + "/" + account.id()), read()));
    }

    private ResultActions list(String query) throws Exception {
        return mockMvc.perform(withToken(get(PATH + "?" + query), read())).andExpect(status().isOk());
    }

    private ResultActions verifyCredentials(String token) throws Exception {
        return mockMvc.perform(withToken(get("/api/v1/accounts/verify_credentials"), token));
    }

    private ResultActions logIn(String username) throws Exception {
        return mockMvc.perform(post("/login").with(csrf()).param("username", username).param("password", PASSWORD));
    }

    private Status publish(Actor author) {
        return statuses.create(author.id(), new NewStatus("Hello", "", null, Visibility.PUBLIC, false, null));
    }

    private Actor newAccount() {
        var username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 25);
        try {
            signUp(username);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return actors.findLocalByUsername(username).orElseThrow();
    }

    private Actor newAccountAsAdmin() {
        var account = newAccount();
        TestAccounts.makeAdmin(jdbc, account.id());
        return account;
    }

    private Actor newRemote() {
        var domain = "r" + UUID.randomUUID() + ".example";
        var uri = URI.create("https://" + domain + "/users/bob");
        return actors.upsertRemote(new RemoteActorProfile(uri, "bob", domain, URI.create(uri + "/inbox"), null, null, null));
    }

    /**
     * Makes these the only admins. Other tests' admins share this database.
     */
    private void onlyAdmin(Actor... remaining) {
        jdbc.sql("UPDATE account_credential SET role = 'USER' WHERE actor_id NOT IN (:ids)")
                .param("ids", Arrays.stream(remaining).map(Actor::id).toList())
                .update();
    }

    private void signUp(String username) throws Exception {
        mockMvc.perform(post("/signup").with(csrf())
                        .param("invite", TestAccounts.invite(jdbc))
                        .param("username", username)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(status().is3xxRedirection());
    }

    private long count(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    private String read() throws Exception {
        return token("admin:read:accounts");
    }

    private String write() throws Exception {
        return token("admin:write:accounts");
    }

    private String token(String scope) throws Exception {
        var cached = TOKENS.get(scope);
        if (cached != null) {
            return cached;
        }
        var token = TestOAuth.userToken(mockMvc, ADMIN, PASSWORD, scope);
        TOKENS.put(scope, token);
        return token;
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }
}
