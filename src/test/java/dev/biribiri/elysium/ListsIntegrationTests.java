package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.graph.AccountLists;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lists, their members, and their timelines.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://elysium.example",
        "elysium.jobs.worker-enabled=false"
})
@AutoConfigureMockMvc
class ListsIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";
    private static final Map<String, String> TOKENS = new HashMap<>();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;

    @BeforeEach
    void setUp() {
        TestAccounts.makeSureAnAccountExists(actors);
        jdbc.sql("DELETE FROM account_list").update();
        jdbc.sql("DELETE FROM status").update();
        jdbc.sql("DELETE FROM follow").update();
        jdbc.sql("DELETE FROM account_block").update();
        jdbc.sql("DELETE FROM account_mute").update();
    }

    @Test
    void createsChangesAndDeletesLists() throws Exception {
        var token = token("listalice");
        var created = mockMvc.perform(withToken(post("/api/v1/lists"), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("title", "  Friends  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Friends"))
                .andExpect(jsonPath("$.replies_policy").value("list"))
                .andExpect(jsonPath("$.exclusive").value(false))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        var second = createList(token, """
                {"title": "Work", "replies_policy": "none", "exclusive": true}""");

        mockMvc.perform(withToken(get("/api/v1/lists"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(id, second)));
        mockMvc.perform(withToken(get("/api/v1/lists/" + second), token))
                .andExpect(jsonPath("$.replies_policy").value("none"))
                .andExpect(jsonPath("$.exclusive").value(true));

        mockMvc.perform(withToken(put("/api/v1/lists/" + id), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"replies_policy\": \"followed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Friends"))
                .andExpect(jsonPath("$.replies_policy").value("followed"))
                .andExpect(jsonPath("$.exclusive").value(false));
        mockMvc.perform(withToken(put("/api/v1/lists/" + id), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("title", "Pals")
                        .param("exclusive", "1"))
                .andExpect(jsonPath("$.title").value("Pals"))
                .andExpect(jsonPath("$.replies_policy").value("followed"))
                .andExpect(jsonPath("$.exclusive").value(true));

        mockMvc.perform(withToken(delete("/api/v1/lists/" + id), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(withToken(get("/api/v1/lists/" + id), token)).andExpect(status().isNotFound());
        mockMvc.perform(withToken(delete("/api/v1/lists/" + id), token)).andExpect(status().isNotFound());
    }

    @Test
    void refusesWhatMastodonRefuses() throws Exception {
        var token = token("listalice");
        mockMvc.perform(withToken(post("/api/v1/lists"), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("Validation failed: Title can't be blank"));
        mockMvc.perform(withToken(post("/api/v1/lists"), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("title", "   "))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(withToken(post("/api/v1/lists"), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("title", "x".repeat(AccountLists.MAX_TITLE_LENGTH + 1)))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(withToken(post("/api/v1/lists"), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("title", "Odd")
                        .param("replies_policy", "some"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("'some' is not a valid replies_policy"));

        for (var i = 0; i < AccountLists.MAX_LISTS; i++) {
            createList(token, "{\"title\": \"List " + i + "\"}");
        }
        mockMvc.perform(withToken(post("/api/v1/lists"), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("title", "One too many"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void anotherUsersListIsNotFound() throws Exception {
        var alice = token("listalice");
        var bob = token("listbob");
        var id = createList(alice, "{\"title\": \"Mine\"}");
        mockMvc.perform(withToken(get("/api/v1/lists/" + id), bob)).andExpect(status().isNotFound());
        mockMvc.perform(withToken(put("/api/v1/lists/" + id), bob)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"Ours\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(delete("/api/v1/lists/" + id), bob)).andExpect(status().isNotFound());
        mockMvc.perform(withToken(get("/api/v1/lists/" + id + "/accounts"), bob)).andExpect(status().isNotFound());
        mockMvc.perform(withToken(get("/api/v1/timelines/list/" + id), bob)).andExpect(status().isNotFound());
        mockMvc.perform(withToken(get("/api/v1/lists"), bob)).andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(withToken(get("/api/v1/lists/not-a-uuid"), alice)).andExpect(status().isNotFound());
    }

    @Test
    void needsTheListsScopes() throws Exception {
        token("listalice");
        var readStatuses = TestOAuth.userToken(mockMvc, "listalice", PASSWORD, "read:statuses");
        mockMvc.perform(withToken(get("/api/v1/lists"), readStatuses)).andExpect(status().isForbidden());
        var readLists = TestOAuth.userToken(mockMvc, "listalice", PASSWORD, "read:lists");
        mockMvc.perform(withToken(get("/api/v1/lists"), readLists)).andExpect(status().isOk());
        mockMvc.perform(withToken(post("/api/v1/lists"), readLists)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("title", "No"))
                .andExpect(status().isForbidden());
    }

    @Test
    void addsOnlyFollowedAccountsAllOrNothing() throws Exception {
        var alice = token("listalice");
        var bob = account("listbob");
        var carol = account("listcarol");
        follow(alice, bob);
        var id = createList(alice, "{\"title\": \"Friends\"}");

        mockMvc.perform(withToken(post("/api/v1/lists/" + id + "/accounts"), alice)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("account_ids[]", bob.id().toString(), carol.id().toString()))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(get("/api/v1/lists/" + id + "/accounts"), alice))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(withToken(post("/api/v1/lists/" + id + "/accounts"), alice)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("account_ids[]", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());

        addMembers(alice, id, bob);
        mockMvc.perform(withToken(post("/api/v1/lists/" + id + "/accounts"), alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account_ids\": [\"" + bob.id() + "\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("Validation failed: Account has already been taken"));
        mockMvc.perform(withToken(get("/api/v1/lists/" + id + "/accounts"), alice))
                .andExpect(jsonPath("$[*].id", contains(bob.id().toString())));
        mockMvc.perform(withToken(get("/api/v1/accounts/" + bob.id() + "/lists"), alice))
                .andExpect(jsonPath("$[*].id", contains(id)));
        mockMvc.perform(withToken(get("/api/v1/accounts/" + carol.id() + "/lists"), alice))
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(withToken(delete("/api/v1/lists/" + id + "/accounts"), alice)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("account_ids[]", bob.id().toString(), carol.id().toString()))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(get("/api/v1/lists/" + id + "/accounts"), alice))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void unfollowingOrBlockingTakesAnAccountOut() throws Exception {
        var alice = token("listalice");
        var bob = account("listbob");
        var carol = account("listcarol");
        follow(alice, bob);
        follow(alice, carol);
        var id = createList(alice, "{\"title\": \"Friends\"}");
        addMembers(alice, id, bob, carol);

        mockMvc.perform(withToken(post("/api/v1/accounts/" + bob.id() + "/unfollow"), alice))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(post("/api/v1/accounts/" + carol.id() + "/block"), alice))
                .andExpect(status().isOk());

        mockMvc.perform(withToken(get("/api/v1/lists/" + id + "/accounts"), alice))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void pagesMembersAndListsThemAllForALimitOfZero() throws Exception {
        var alice = token("listalice");
        var bob = account("listbob");
        var carol = account("listcarol");
        var dave = account("listdave");
        follow(alice, bob);
        follow(alice, carol);
        follow(alice, dave);
        var id = createList(alice, "{\"title\": \"Friends\"}");
        addMembers(alice, id, bob);
        addMembers(alice, id, carol);
        addMembers(alice, id, dave);

        var first = mockMvc.perform(withToken(get("/api/v1/lists/" + id + "/accounts").param("limit", "2"), alice))
                .andExpect(jsonPath("$[*].id", contains(dave.id().toString(), carol.id().toString())))
                .andExpect(header().string("Link", containsString("rel=\"next\"")))
                .andReturn().getResponse().getHeader("Link");
        var next = first.substring(first.indexOf('<') + 1, first.indexOf('>'));
        mockMvc.perform(withToken(get(next.replace("https://elysium.example", "")), alice))
                .andExpect(jsonPath("$[*].id", contains(bob.id().toString())));

        mockMvc.perform(withToken(get("/api/v1/lists/" + id + "/accounts").param("limit", "0"), alice))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(header().doesNotExist("Link"));
    }

    @Test
    void aWaitingRequestCanBeAddedButIsNotListed() throws Exception {
        var alice = token("listalice");
        var locked = token("listlocked");
        mockMvc.perform(withToken(patch("/api/v1/accounts/update_credentials"), locked)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("locked", "true"))
                .andExpect(status().isOk());
        var lockedAccount = actors.findLocalByUsername("listlocked").orElseThrow();
        createStatus(locked, "Before", "public", null);
        follow(alice, lockedAccount);
        var id = createList(alice, "{\"title\": \"Waiting\"}");
        addMembers(alice, id, lockedAccount);

        mockMvc.perform(withToken(get("/api/v1/lists/" + id + "/accounts"), alice))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(withToken(get("/api/v1/timelines/list/" + id), alice))
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(withToken(post("/api/v1/follow_requests/" + actors.findLocalByUsername("listalice")
                        .orElseThrow().id() + "/authorize"), locked))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(get("/api/v1/timelines/list/" + id), alice))
                .andExpect(jsonPath("$[*].content", contains("<p>Before</p>")));
    }

    @Test
    void theTimelineListsMembersByItsRepliesPolicy() throws Exception {
        var alice = token("listalice");
        var bob = token("listbob");
        var carol = token("listcarol");
        var dave = token("listdave");
        var bobAccount = actors.findLocalByUsername("listbob").orElseThrow();
        var carolAccount = actors.findLocalByUsername("listcarol").orElseThrow();
        var daveAccount = actors.findLocalByUsername("listdave").orElseThrow();
        follow(alice, bobAccount);
        follow(alice, carolAccount);
        follow(alice, daveAccount);
        var followed = createList(alice, "{\"title\": \"Followed\", \"replies_policy\": \"followed\"}");
        var members = createList(alice, "{\"title\": \"Members\", \"replies_policy\": \"list\"}");
        var none = createList(alice, "{\"title\": \"None\", \"replies_policy\": \"none\"}");
        for (var list : List.of(followed, members, none)) {
            addMembers(alice, list, bobAccount, carolAccount);
        }

        var byBob = createStatus(bob, "Plain", "public", null);
        var byCarol = createStatus(carol, "From carol", "public", null);
        var byDave = createStatus(dave, "From dave", "public", null);
        var byAlice = createStatus(alice, "From alice", "public", null);
        var toSelf = createStatus(bob, "Thread", "public", byBob);
        var toMember = createStatus(bob, "To carol", "public", byCarol);
        var toFollowed = createStatus(bob, "To dave", "public", byDave);
        var toOwner = createStatus(bob, "To alice", "public", byAlice);
        var direct = createStatus(bob, "@listalice secret", "direct", null);
        var boost = JsonPath.<String>read(mockMvc.perform(withToken(post("/api/v1/statuses/" + byDave + "/reblog"), carol))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");

        mockMvc.perform(withToken(get("/api/v1/timelines/list/" + followed), alice))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(byBob, byCarol, toSelf, toMember, toFollowed,
                        toOwner, boost)));
        mockMvc.perform(withToken(get("/api/v1/timelines/list/" + members), alice))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(byBob, byCarol, toSelf, toMember, toOwner, boost)));
        mockMvc.perform(withToken(get("/api/v1/timelines/list/" + none), alice))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(byBob, byCarol, toSelf, toOwner, boost)));
        // The home timeline still has the direct status, which lists leave out.
        mockMvc.perform(withToken(get("/api/v1/timelines/home"), alice))
                .andExpect(jsonPath("$[*].id", hasItem(direct)));

        mockMvc.perform(withToken(post("/api/v1/accounts/" + carolAccount.id() + "/mute"), alice))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(get("/api/v1/timelines/list/" + none), alice))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(byBob, toSelf, toOwner)));
    }

    @Test
    void anExclusiveListKeepsItsMembersOutOfHome() throws Exception {
        var alice = token("listalice");
        var bob = token("listbob");
        var carol = token("listcarol");
        var bobAccount = actors.findLocalByUsername("listbob").orElseThrow();
        var carolAccount = actors.findLocalByUsername("listcarol").orElseThrow();
        follow(alice, bobAccount);
        follow(alice, carolAccount);
        var id = createList(alice, "{\"title\": \"Loud\", \"exclusive\": true}");
        addMembers(alice, id, bobAccount);

        var byBob = createStatus(bob, "Loud", "public", null);
        var byCarol = createStatus(carol, "Quiet", "public", null);
        var byAlice = createStatus(alice, "Mine", "public", null);
        var boost = JsonPath.<String>read(mockMvc.perform(withToken(post("/api/v1/statuses/" + byCarol + "/reblog"), bob))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");

        mockMvc.perform(withToken(get("/api/v1/timelines/home"), alice))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(byCarol, byAlice)))
                .andExpect(jsonPath("$[*].id", not(hasItem(boost))));
        mockMvc.perform(withToken(get("/api/v1/timelines/list/" + id), alice))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(byBob, boost)));

        mockMvc.perform(withToken(put("/api/v1/lists/" + id), alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"exclusive\": false}"))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(get("/api/v1/timelines/home"), alice))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(byBob, byCarol, byAlice, boost)));
    }

    private String createList(String token, String json) throws Exception {
        var body = mockMvc.perform(withToken(post("/api/v1/lists"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private void addMembers(String token, String listId, Actor... accounts) throws Exception {
        var request = withToken(post("/api/v1/lists/" + listId + "/accounts"), token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED);
        for (var account : accounts) {
            request.param("account_ids[]", account.id().toString());
        }
        mockMvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    private String createStatus(String token, String text, String visibility, String inReplyTo) throws Exception {
        var request = withToken(post("/api/v1/statuses"), token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("status", text)
                .param("visibility", visibility);
        if (inReplyTo != null) request.param("in_reply_to_id", inReplyTo);
        var body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private void follow(String token, Actor followed) throws Exception {
        mockMvc.perform(withToken(post("/api/v1/accounts/" + followed.id() + "/follow"), token))
                .andExpect(status().isOk());
    }

    private Actor account(String username) throws Exception {
        token(username);
        return actors.findLocalByUsername(username).orElseThrow();
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String token(String username) throws Exception {
        var cached = TOKENS.get(username);
        if (cached != null) {
            return cached;
        }
        mockMvc.perform(post("/signup").with(csrf())
                        .param("invite", TestAccounts.invite(jdbc))
                        .param("username", username)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(status().is3xxRedirection());
        var token = TestOAuth.userToken(mockMvc, username, PASSWORD, "read write");
        TOKENS.put(username, token);
        return token;
    }
}
