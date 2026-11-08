package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.TestAccounts;
import dev.biribiri.elysium.TestOAuth;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.ActorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Invites, with sign-ups closed, which is the default mode.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.instance.base-url=https://invites.example")
@AutoConfigureMockMvc
class InviteIntegrationTests {

    private static final String PASSWORD = "a long enough password";

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ActorService actors;

    @Autowired
    private LocalAccounts accounts;

    @Autowired
    private Invites invites;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void makeSureAnAccountExists() {
        TestAccounts.makeSureAnAccountExists(actors);
    }

    @Test
    void signUpsWithoutAnInviteAreClosed() throws Exception {
        mockMvc.perform(get("/signup")).andExpect(status().isNotFound());
        mockMvc.perform(get("/signup/verify").param("token", "anything")).andExpect(status().isNotFound());
        mockMvc.perform(get("/login")).andExpect(content().string(not(containsString("/signup"))));
        mockMvc.perform(get("/api/v1/instance"))
                .andExpect(jsonPath("$.registrations").value(false))
                .andExpect(jsonPath("$.approval_required").value(false));
        mockMvc.perform(get("/nodeinfo/2.1")).andExpect(jsonPath("$.openRegistrations").value(false));
    }

    @Test
    void anInviteMakesAnAccountWithoutAnEmailAddress() throws Exception {
        var invite = invites.create(null, null);
        assertThat(invite.url().toString()).isEqualTo("https://invites.example/signup?invite=" + invite.code());

        mockMvc.perform(get("/signup").param("invite", invite.code()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"invite\"")))
                .andExpect(content().string(not(containsString("name=\"email\""))));
        signUp(invite.code(), "invited").andExpect(redirectedUrl("/login?registered"));

        var actor = actors.findLocalByUsername("invited").orElseThrow();
        assertThat(accounts.emailsOf(List.of(actor.id()))).isEmpty();
        assertThat(accounts.isAdmin(actor.id())).isFalse();
        assertThat(invites.list().stream().filter(found -> found.id().equals(invite.id())).findFirst().orElseThrow().uses())
                .isOne();
    }

    @Test
    void anInviteCanBeUsedUp() throws Exception {
        var invite = invites.create(1, null);

        signUp(invite.code(), "usedonce").andExpect(redirectedUrl("/login?registered"));
        signUp(invite.code(), "usedtwice")
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("used up")));
        mockMvc.perform(get("/signup").param("invite", invite.code())).andExpect(status().isNotFound());

        assertThat(actors.findAnyLocalByUsername("usedtwice")).isEmpty();
    }

    @Test
    void aTakenUsernameDoesntUseUpTheInvite() throws Exception {
        var invite = invites.create(1, null);
        assertThat(accounts.signUp("alreadyhere", PASSWORD)).isTrue();

        signUp(invite.code(), "alreadyhere").andExpect(status().isUnprocessableContent());

        assertThat(invites.isUsable(invite.code())).isTrue();
    }

    @Test
    void anExpiredOrRevokedInviteDoesntWork() throws Exception {
        var expired = invites.create(null, Instant.now().minusSeconds(1));
        var revoked = invites.create(null, null);
        assertThat(invites.revoke(revoked.id())).isTrue();
        assertThat(invites.revoke(revoked.id())).isFalse();

        signUp(expired.code(), "expiredinvite").andExpect(status().isNotFound());
        signUp(revoked.code(), "revokedinvite").andExpect(status().isNotFound());
        signUp("not-an-invite", "noinvite").andExpect(status().isNotFound());

        assertThat(actors.findAnyLocalByUsername("expiredinvite")).isEmpty();
        assertThat(actors.findAnyLocalByUsername("revokedinvite")).isEmpty();
    }

    @Test
    void onlyAHashIsStored() {
        var invite = invites.create(null, null);

        var stored = jdbc.sql("SELECT code_hash FROM invite WHERE id = :id")
                .param("id", invite.id())
                .query(String.class)
                .single();
        assertThat(stored).isNotEqualTo(invite.code()).isEqualTo(SecretCodes.hash(invite.code()));
    }

    @Test
    void commandsCreateListAndRevoke() {
        assertThat(run(createInvite(), "--max-uses", "2", "--expires-in", "7d")).isZero();
        var printed = out.toString().lines().toList();
        assertThat(printed.get(1)).startsWith("https://invites.example/signup?invite=");
        var id = printed.get(0).replace("Created invite ", "");
        assertThat(invites.isUsable(printed.get(1).substring(printed.get(1).indexOf('=') + 1))).isTrue();

        var list = new ListInvitesCommand();
        list.wire(invites);
        assertThat(run(list)).isZero();
        assertThat(out.toString()).contains(id + "  created ", "0 of 2 uses", "expires ");

        var revoke = new RevokeInviteCommand();
        revoke.wire(invites);
        assertThat(run(revoke, id)).isZero();
        assertThat(run(revoke, id)).isOne();
        assertThat(run(list)).isZero();
        assertThat(out.toString()).contains("revoked");

        assertThat(run(createInvite(), "--max-uses", "0")).isOne();
        assertThat(run(createInvite(), "--expires-in", "soon")).isOne();
        assertThat(err.toString()).contains("--max-uses", "--expires-in");
    }

    @Test
    void anAdminCanCreateAnInviteFromTheAdminPage() throws Exception {
        var admin = adminSession();

        var page = mockMvc.perform(post("/admin/invites").session(admin).with(csrf())
                        .param("max_uses", "3")
                        .param("expires_in", "7 days"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("can't be shown again")))
                .andReturn().getResponse().getContentAsString();
        var code = page.replaceAll("(?s).*https://invites\\.example/signup\\?invite=([^<\"&]+).*", "$1");
        assertThat(invites.isUsable(code)).isTrue();
        var created = invites.list().getFirst();
        assertThat(created.maxUses()).isEqualTo(3);
        assertThat(created.expiresAt()).isBetween(Instant.now().plusSeconds(6 * 86400), Instant.now().plusSeconds(7 * 86400));

        mockMvc.perform(get("/admin/invites").session(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("0 of 3 uses")))
                .andExpect(content().string(not(containsString(code))));
    }

    @Test
    void theAdminPageRefusesBadInvites() throws Exception {
        var admin = adminSession();
        var before = invites.list().size();

        for (var maxUses : List.of("0", "-1", "lots")) {
            mockMvc.perform(post("/admin/invites").session(admin).with(csrf()).param("max_uses", maxUses))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(content().string(containsString("at least 1")));
        }
        mockMvc.perform(post("/admin/invites").session(admin).with(csrf()).param("expires_in", "a century"))
                .andExpect(status().isUnprocessableContent());

        assertThat(invites.list()).hasSize(before);
    }

    @Test
    void anAdminCanRevokeAnInviteFromTheAdminPage() throws Exception {
        var admin = adminSession();
        var invite = invites.create(null, null);

        mockMvc.perform(post("/admin/invites/" + invite.id() + "/revoke").session(admin).with(csrf()))
                .andExpect(redirectedUrl("/admin/invites"));
        assertThat(invites.isUsable(invite.code())).isFalse();

        mockMvc.perform(post("/admin/invites/" + invite.id() + "/revoke").session(admin).with(csrf()))
                .andExpect(redirectedUrl("/admin/invites"));
        mockMvc.perform(post("/admin/invites/not-an-id/revoke").session(admin).with(csrf()))
                .andExpect(redirectedUrl("/admin/invites"));
        mockMvc.perform(get("/admin/invites").session(admin))
                .andExpect(content().string(containsString("Revoked")))
                .andExpect(content().string(not(containsString("/admin/invites/" + invite.id() + "/revoke"))));
    }

    @Test
    void onlyAdminsCanManageInvites() throws Exception {
        var username = "notadmin" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(accounts.signUp(username, PASSWORD)).isTrue();
        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);
        var invite = invites.create(null, null);

        mockMvc.perform(get("/admin/invites").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/invites").session(session).with(csrf())).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/invites/" + invite.id() + "/revoke").session(session).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/invites").session(adminSession())).andExpect(status().isForbidden());

        assertThat(invites.isUsable(invite.code())).isTrue();
    }

    private MockHttpSession adminSession() throws Exception {
        var username = "admin" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(accounts.signUp(username, PASSWORD)).isTrue();
        accounts.setRole(actors.findLocalByUsername(username).orElseThrow().id(), AccountRole.ADMIN);
        return TestOAuth.logIn(mockMvc, username, PASSWORD);
    }

    private ResultActions signUp(String invite, String username) throws Exception {
        return mockMvc.perform(post("/signup").with(csrf())
                .param("invite", invite)
                .param("username", username)
                .param("password", PASSWORD)
                .param("confirmPassword", PASSWORD));
    }

    private CreateInviteCommand createInvite() {
        var command = new CreateInviteCommand();
        command.wire(invites);
        return command;
    }

    private int run(Callable<Integer> command, String... args) {
        return new CommandLine(command)
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute(args);
    }
}
