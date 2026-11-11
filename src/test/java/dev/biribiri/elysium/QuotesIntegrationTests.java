package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import dev.biribiri.elysium.content.QuoteApproval;
import dev.biribiri.elysium.content.QuoteService;
import dev.biribiri.elysium.content.RemoteQuote;
import dev.biribiri.elysium.content.RemoteStatus;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quoting statuses through the client API. No job worker runs, so activities are only queued. Quotes from other servers
 * are tested in {@code QuoteIntegrationTests}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://quotes.example",
        "elysium.jobs.worker-enabled=false"
})
@AutoConfigureMockMvc
class QuotesIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String PUBLIC = "https://www.w3.org/ns/activitystreams#Public";
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Map<String, String> TOKENS = new HashMap<>();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;
    @Autowired
    private StatusService statuses;
    @Autowired
    private QuoteService quotes;
    @MockitoBean
    private RemoteActorResolver resolver;

    @BeforeEach
    void setUp() {
        TestAccounts.makeSureAnAccountExists(actors);
        jdbc.sql("DELETE FROM follow").update();
        jdbc.sql("DELETE FROM account_block").update();
        jdbc.sql("DELETE FROM account_mute").update();
        jdbc.sql("DELETE FROM posting_default").update();
    }

    @Test
    void aQuoteOfALocalStatusIsAcceptedAndNotifiesItsAuthor() throws Exception {
        var bob = account("qbob");
        var quoted = createStatus("qbob", Map.of("status", "original"));
        var quote = createStatus("qalice", Map.of("status", "look at this", "quoted_status_id", quoted));

        mockMvc.perform(withToken(get("/api/v1/statuses/" + quote), token("qalice")))
                .andExpect(jsonPath("$.quote.state").value("accepted"))
                .andExpect(jsonPath("$.quote.quoted_status.id").value(quoted))
                .andExpect(jsonPath("$.quote.quoted_status.quote").doesNotExist())
                .andExpect(jsonPath("$.content", startsWith("<p class=\"quote-inline\">RE: <a href=\"https://quotes.example/users/qbob/statuses/" + quoted)))
                .andExpect(jsonPath("$.content", containsString("<p>look at this</p>")));
        mockMvc.perform(withToken(get("/api/v1/statuses/" + quoted), token("qbob")))
                .andExpect(jsonPath("$.quotes_count").value(1))
                .andExpect(jsonPath("$.quote_approval.automatic[0]").value("public"))
                .andExpect(jsonPath("$.quote_approval.current_user").value("automatic"));
        await().atMost(WAIT).untilAsserted(() -> mockMvc.perform(
                        withToken(get("/api/v1/notifications").param("types[]", "quote"), token("qbob")))
                .andExpect(jsonPath("$[0].type").value("quote"))
                .andExpect(jsonPath("$[0].status.id").value(quote)));
        assertThat(jdbc.sql("SELECT silent FROM status_mention WHERE status_id = :id AND actor_id = :bob")
                .param("id", UUID.fromString(quote)).param("bob", bob.id()).query(Boolean.class).single()).isTrue();
    }

    @Test
    void aTextThatLinksTheQuotedStatusHasNoFallback() throws Exception {
        account("qbob");
        var quoted = createStatus("qbob", Map.of("status", "original"));
        var link = "https://quotes.example/users/qbob/statuses/" + quoted;
        var quote = createStatus("qalice", Map.of("status", "see " + link, "quoted_status_id", quoted));

        mockMvc.perform(withToken(get("/api/v1/statuses/" + quote), token("qalice")))
                .andExpect(jsonPath("$.content", startsWith("<p>see ")));
    }

    @Test
    void theAuthorsPolicyDecidesWhoCanQuote() throws Exception {
        var bob = account("qbob");
        var nobody = createStatus("qbob", Map.of("status", "mine", "quote_approval_policy", "nobody"));
        var followers = createStatus("qbob", Map.of("status", "theirs", "quote_approval_policy", "followers"));

        quote("qalice", nobody).andExpect(status().isUnprocessableContent());
        quote("qalice", followers).andExpect(status().isUnprocessableContent());
        mockMvc.perform(withToken(get("/api/v1/statuses/" + nobody), token("qalice")))
                .andExpect(jsonPath("$.quote_approval.automatic").isEmpty())
                .andExpect(jsonPath("$.quote_approval.current_user").value("denied"));
        mockMvc.perform(withToken(post("/api/v1/accounts/" + bob.id() + "/follow"), token("qalice")))
                .andExpect(status().isOk());
        quote("qalice", followers).andExpect(status().isOk());
        quote("qbob", nobody).andExpect(status().isOk());
        quote("qalice", UUID.randomUUID().toString()).andExpect(status().isNotFound());
    }

    @Test
    void theAccountsDefaultAppliesAndCanBeChanged() throws Exception {
        mockMvc.perform(withToken(patch("/api/v1/accounts/update_credentials"), token("qbob"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("source[quote_policy]", "followers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source.quote_policy").value("followers"));
        var id = createStatus("qbob", Map.of("status", "by default"));
        mockMvc.perform(withToken(get("/api/v1/statuses/" + id), token("qbob")))
                .andExpect(jsonPath("$.quote_approval.automatic[0]").value("followers"));
        mockMvc.perform(withToken(patch("/api/v1/accounts/update_credentials"), token("qbob"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("source[quote_policy]", "everyone"))
                .andExpect(status().isUnprocessableContent());

        mockMvc.perform(withToken(put("/api/v1/statuses/" + id + "/interaction_policy"), token("qbob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quote_approval_policy\":\"public\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quote_approval.automatic[0]").value("public"));
        mockMvc.perform(withToken(put("/api/v1/statuses/" + id + "/interaction_policy"), token("qalice"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("quote_approval_policy", "nobody"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aFollowersOnlyStatusCanOnlyBeQuotedByItsAuthorWhichKeepsTheQuoteFollowersOnly() throws Exception {
        var bob = account("qbob");
        mockMvc.perform(withToken(post("/api/v1/accounts/" + bob.id() + "/follow"), token("qalice")))
                .andExpect(status().isOk());
        var privately = createStatus("qbob", Map.of("status", "between us", "visibility", "private"));

        quote("qalice", privately).andExpect(status().isUnprocessableContent());
        quote("qbob", privately).andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("private"))
                .andExpect(jsonPath("$.quote.state").value("accepted"))
                .andExpect(jsonPath("$.quote_approval.automatic").isEmpty());
    }

    @Test
    void revokingAQuoteHidesWhatItQuotes() throws Exception {
        account("qbob");
        var quoted = createStatus("qbob", Map.of("status", "original"));
        var quote = createStatus("qalice", Map.of("status", "quoting", "quoted_status_id", quoted));
        var other = createStatus("qalice", Map.of("status", "not a quote"));

        revoke("qalice", quoted, quote).andExpect(status().isForbidden());
        revoke("qbob", quoted, other).andExpect(status().isNotFound());
        revoke("qbob", quoted, quote).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(quote))
                .andExpect(jsonPath("$.quote.state").value("revoked"))
                .andExpect(jsonPath("$.quote.quoted_status").isEmpty());
        revoke("qbob", quoted, quote).andExpect(status().isOk());
        mockMvc.perform(withToken(get("/api/v1/statuses/" + quoted), token("qbob")))
                .andExpect(jsonPath("$.quotes_count").value(0));
    }

    @Test
    void listsAStatusesAcceptedQuotesNewestFirst() throws Exception {
        account("qbob");
        var quoted = createStatus("qbob", Map.of("status", "original"));
        var first = createStatus("qalice", Map.of("status", "one", "quoted_status_id", quoted));
        var second = createStatus("qalice", Map.of("status", "two", "quoted_status_id", quoted));
        var revoked = createStatus("qalice", Map.of("status", "three", "quoted_status_id", quoted));
        revoke("qbob", quoted, revoked).andExpect(status().isOk());

        var body = mockMvc.perform(withToken(get("/api/v1/statuses/" + quoted + "/quotes").param("limit", "1"),
                        token("qbob")))
                .andExpect(status().isOk())
                .andExpect(header().string("Link", containsString("max_id=" + second)))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$[*].id")).containsExactly(second);
        body = mockMvc.perform(withToken(get("/api/v1/statuses/" + quoted + "/quotes").param("max_id", second),
                        token("qbob")))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$[*].id")).containsExactly(first);
    }

    @Test
    void showsWhyAQuotedStatusIsHidden() throws Exception {
        var bob = account("qbob");
        var quoted = createStatus("qbob", Map.of("status", "original"));
        var quote = createStatus("qcarol", Map.of("status", "quoting", "quoted_status_id", quoted));

        mockMvc.perform(withToken(post("/api/v1/accounts/" + bob.id() + "/mute"), token("qalice")))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(get("/api/v1/statuses/" + quote), token("qalice")))
                .andExpect(jsonPath("$.quote.state").value("muted_account"))
                .andExpect(jsonPath("$.quote.quoted_status.id").value(quoted));
        mockMvc.perform(withToken(post("/api/v1/accounts/" + bob.id() + "/block"), token("qalice")))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(get("/api/v1/statuses/" + quote), token("qalice")))
                .andExpect(jsonPath("$.quote.state").value("blocked_account"));

        mockMvc.perform(withToken(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v1/statuses/" + quoted), token("qbob")))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(get("/api/v1/statuses/" + quote), token("qcarol")))
                .andExpect(jsonPath("$.quote.state").value("deleted"))
                .andExpect(jsonPath("$.quote.quoted_status").isEmpty());
    }

    @Test
    void aQuoteCantBeChangedByAnEdit() throws Exception {
        account("qbob");
        var quoted = createStatus("qbob", Map.of("status", "original"));
        var other = createStatus("qbob", Map.of("status", "another"));
        var quote = createStatus("qalice", Map.of("status", "quoting", "quoted_status_id", quoted));

        mockMvc.perform(withToken(put("/api/v1/statuses/" + quote), token("qalice"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", "edited")
                        .param("quoted_status_id", other))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(withToken(put("/api/v1/statuses/" + quote), token("qalice"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", "edited")
                        .param("quoted_status_id", quoted))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", startsWith("<p class=\"quote-inline\">")))
                .andExpect(jsonPath("$.quote.state").value("accepted"));
    }

    @Test
    void aQuoteOfARemoteStatusWaitsForItsAuthorsApproval() throws Exception {
        var alice = account("qalice");
        var remote = remote();
        var quoted = storeRemote(remote, new QuoteApproval(Set.of(QuoteApproval.Audience.PUBLIC), Set.of()));
        var quote = createStatus("qalice", Map.of("status", "quoting", "quoted_status_id", quoted.toString()));

        mockMvc.perform(withToken(get("/api/v1/statuses/" + quote), token("qalice")))
                .andExpect(jsonPath("$.quote.state").value("pending"))
                .andExpect(jsonPath("$.quote.quoted_status").isEmpty());
        var request = await().atMost(WAIT).until(() -> bodiesOfType(alice, "QuoteRequest", quote), found -> !found.isEmpty())
                .getFirst();
        var quoteUri = "https://quotes.example/users/qalice/statuses/" + quote;
        assertThat(JsonPath.<String>read(request, "$.id")).isEqualTo(quoteUri + "#quote_request");
        assertThat(JsonPath.<String>read(request, "$.object")).isEqualTo(remote.uri() + "/statuses/1");
        assertThat(JsonPath.<String>read(request, "$.instrument.id")).isEqualTo(quoteUri);
        assertThat(JsonPath.<String>read(request, "$.instrument.quote")).isEqualTo(remote.uri() + "/statuses/1");
        assertThat(deliveryInboxes(quoteUri + "#quote_request")).containsExactly(remote.inboxUri().toString());
        var create = await().atMost(WAIT).until(() -> bodiesOfType(alice, "Create", quote), found -> !found.isEmpty())
                .getFirst();
        assertThat(JsonPath.<String>read(create, "$.object.quote")).isEqualTo(remote.uri() + "/statuses/1");
        assertThat(JsonPath.<List<String>>read(create, "$.object.cc")).contains(remote.uri().toString());
        assertThat(JsonPath.<Map<String, Object>>read(create, "$.object"))
                .doesNotContainKeys("_misskey_quote", "quoteUri", "quoteUrl", "quoteAuthorization");
        assertThat(deliveryInboxes(quoteUri + "#create")).contains(remote.sharedInboxUri().toString());
    }

    @Test
    void aStatusFromAServerThatCantBeAskedIsQuotedWithoutWaiting() throws Exception {
        var alice = account("qalice");
        var remote = remote();
        var quoted = storeRemote(remote, null);
        var quote = createStatus("qalice", Map.of("status", "quoting", "quoted_status_id", quoted.toString()));

        mockMvc.perform(withToken(get("/api/v1/statuses/" + quote), token("qalice")))
                .andExpect(jsonPath("$.quote.state").value("accepted"));
        mockMvc.perform(withToken(get("/api/v1/statuses/" + quoted), token("qalice")))
                .andExpect(jsonPath("$.quote_approval.automatic[0]").value("unsupported_policy"))
                .andExpect(jsonPath("$.quote_approval.current_user").value("automatic"));
        var create = await().atMost(WAIT).until(() -> bodiesOfType(alice, "Create", quote), found -> !found.isEmpty())
                .getFirst();
        var quotedUri = remote.uri() + "/statuses/1";
        assertThat(JsonPath.<String>read(create, "$.object._misskey_quote")).isEqualTo(quotedUri);
        assertThat(JsonPath.<String>read(create, "$.object.quoteUri")).isEqualTo(quotedUri);
        assertThat(JsonPath.<String>read(create, "$.object.quoteUrl")).isEqualTo(quotedUri);
        assertThat(JsonPath.<List<String>>read(create, "$.object.tag[?(@.type == 'Link')].href")).containsExactly(quotedUri);
        assertThat(JsonPath.<Map<String, Object>>read(create, "$.object")).doesNotContainKey("quoteAuthorization");
    }

    @Test
    void aLocalQuoteNamesItsApprovalInItsNote() throws Exception {
        account("qalice");
        account("qbob");
        var quoted = createStatus("qbob", Map.of("status", "original"));
        var quote = createStatus("qalice", Map.of("status", "quoting", "quoted_status_id", quoted));

        // Nobody on another server follows alice, so the Note is fetched rather than sent.
        var note = mockMvc.perform(get("/users/qalice/statuses/" + quote).accept("application/activity+json"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var create = "{\"object\":" + note + "}";
        var stamp = JsonPath.<String>read(create, "$.object.quoteAuthorization");
        assertThat(stamp).startsWith("https://quotes.example/users/qbob/quote_authorizations/");
        assertThat(JsonPath.<String>read(create, "$.object.interactionPolicy.canQuote.automaticApproval[0]"))
                .isEqualTo(PUBLIC);
        mockMvc.perform(get(URI.create(stamp).getPath()).accept("application/activity+json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("QuoteAuthorization"))
                .andExpect(jsonPath("$.attributedTo").value("https://quotes.example/users/qbob"))
                .andExpect(jsonPath("$.interactingObject").value(JsonPath.<String>read(create, "$.object.id")))
                .andExpect(jsonPath("$.interactionTarget").value("https://quotes.example/users/qbob/statuses/" + quoted));
        mockMvc.perform(get(URI.create(stamp).getPath().replace("qbob", "qalice")).accept("application/activity+json"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anApprovedRemoteQuoteIsSentAgainAfterItsCreate() throws Exception {
        var alice = account("qalice");
        var remote = remote();
        var quotedId = storeRemote(remote, new QuoteApproval(Set.of(QuoteApproval.Audience.PUBLIC), Set.of()));
        var quote = createStatus("qalice", Map.of("status", "quoting", "quoted_status_id", quotedId.toString()));
        var quoteUri = URI.create("https://quotes.example/users/qalice/statuses/" + quote);
        await().atMost(WAIT).until(() -> bodiesOfType(alice, "Create", quote), found -> !found.isEmpty());
        var stamp = URI.create(remote.uri() + "/quote_authorizations/1");

        assertThat(quotes.answerRequest(URI.create(quoteUri + "#quote_request"), remote.uri(), stamp)).isTrue();

        var update = await().atMost(WAIT).until(() -> bodiesOfType(alice, "Update", quote), found -> !found.isEmpty())
                .getFirst();
        assertThat(JsonPath.<String>read(update, "$.id")).startsWith(quoteUri + "#updates/");
        assertThat(JsonPath.<String>read(update, "$.object.quoteAuthorization")).isEqualTo(stamp.toString());
        assertThat(JsonPath.<String>read(update, "$.object._misskey_quote")).isEqualTo(remote.uri() + "/statuses/1");
        assertThat(Instant.parse(JsonPath.<String>read(update, "$.object.updated")))
                .isAfter(Instant.parse(JsonPath.<String>read(update, "$.object.published")));
        assertThat(deliveryInboxes(JsonPath.read(update, "$.id"))).contains(remote.sharedInboxUri().toString());
    }

    @Test
    void revokingARemoteQuoteDeletesItsApproval() throws Exception {
        var bob = account("qbob");
        var remote = remote();
        var quoted = createStatus("qbob", Map.of("status", "original"));
        var quoteUri = URI.create(remote.uri() + "/statuses/" + UUID.randomUUID());
        var quote = statuses.storeRemote(new RemoteStatus(quoteUri, null, remote.id(), "<p>quote</p>", null, null,
                Visibility.PUBLIC, false, null, Instant.now(), null, Map.of(), List.of(), List.of(),
                new RemoteQuote(URI.create("https://quotes.example/users/qbob/statuses/" + quoted), false, null), null))
                .orElseThrow();
        var stamp = quotes.approveRequest(quote, UUID.fromString(quoted), URI.create(remote.uri() + "/request")).orElseThrow();
        var stampPath = "/users/qbob/quote_authorizations/" + stamp;
        mockMvc.perform(get(stampPath).accept("application/activity+json")).andExpect(status().isOk());

        revoke("qbob", quoted, quote.toString()).andExpect(status().isOk());

        mockMvc.perform(get(stampPath).accept("application/activity+json")).andExpect(status().isNotFound());
        var delete = await().atMost(WAIT).until(() -> bodiesOfType(bob, "Delete", stamp.toString()), found -> !found.isEmpty())
                .getFirst();
        assertThat(JsonPath.<String>read(delete, "$.object.type")).isEqualTo("QuoteAuthorization");
        assertThat(JsonPath.<Map<String, Object>>read(delete, "$.object")).containsOnlyKeys("id", "type");
        assertThat(deliveryInboxes(JsonPath.read(delete, "$.id"))).contains(remote.sharedInboxUri().toString());
    }

    @Test
    void anApprovalOfAFollowersOnlyQuoteIsNotServedToAnyone() throws Exception {
        account("qbob");
        var quoted = createStatus("qbob", Map.of("status", "original"));
        var quote = createStatus("qalice", Map.of("status", "quoting", "quoted_status_id", quoted, "visibility", "private"));
        var stamp = jdbc.sql("SELECT stamp_id FROM status_quote WHERE status_id = :id")
                .param("id", UUID.fromString(quote)).query(UUID.class).single();

        mockMvc.perform(get("/users/qbob/quote_authorizations/" + stamp).accept("application/activity+json"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingAQuoteRemovesItsRecord() throws Exception {
        account("qbob");
        var quoted = createStatus("qbob", Map.of("status", "original"));
        var quote = createStatus("qalice", Map.of("status", "quoting", "quoted_status_id", quoted));

        mockMvc.perform(withToken(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v1/statuses/" + quote), token("qalice")))
                .andExpect(status().isOk());

        assertThat(quotes.find(UUID.fromString(quote))).isEmpty();
        mockMvc.perform(withToken(get("/api/v1/statuses/" + quoted), token("qbob")))
                .andExpect(jsonPath("$.quotes_count").value(0));
    }

    @Test
    void reportsApiVersionSevenForQuotes() throws Exception {
        mockMvc.perform(get("/api/v2/instance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value("quotes.example"))
                .andExpect(jsonPath("$.api_versions.mastodon").value(7));
    }

    private org.springframework.test.web.servlet.ResultActions quote(String username, String quoted) throws Exception {
        return mockMvc.perform(withToken(post("/api/v1/statuses"), token(username))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("status", "quoting")
                .param("quoted_status_id", quoted));
    }

    private org.springframework.test.web.servlet.ResultActions revoke(String username, String quoted, String quoting)
            throws Exception {
        return mockMvc.perform(withToken(post("/api/v1/statuses/" + quoted + "/quotes/" + quoting + "/revoke"),
                token(username)));
    }

    private UUID storeRemote(Actor author, QuoteApproval policy) {
        jdbc.sql("DELETE FROM status WHERE uri = :uri").param("uri", author.uri() + "/statuses/1").update();
        return statuses.storeRemote(new RemoteStatus(URI.create(author.uri() + "/statuses/1"), null, author.id(),
                "<p>remote</p>", null, null, Visibility.PUBLIC, false, null, Instant.now(), null, Map.of(), List.of(),
                List.of(), null, policy)).orElseThrow();
    }

    private Actor remote() {
        var domain = "q" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + domain + "/users/" + UUID.randomUUID());
        return actors.upsertRemote(new RemoteActorProfile(
                uri, "dave", domain, URI.create(uri + "/inbox"), null, URI.create("https://" + domain + "/inbox"), null,
                URI.create(uri + "/followers")));
    }

    /**
     * @param status a status whose ID the activity has to contain
     */
    private List<String> bodiesOfType(Actor actor, String type, String status) {
        return jdbc.sql("SELECT body FROM outbound_activity WHERE actor_id = :actorId AND activity_id LIKE :status")
                .param("actorId", actor.id())
                .param("status", "%" + status + "%")
                .query(String.class)
                .list()
                .stream()
                .filter(body -> JsonPath.<String>read(body, "$.type").equals(type))
                .toList();
    }

    private List<String> deliveryInboxes(String activityId) {
        return jdbc.sql("""
                        SELECT convert_from(task_data, 'UTF8')::json ->> 'inbox' FROM scheduled_tasks
                        WHERE task_name = 'activity-delivery'
                          AND convert_from(task_data, 'UTF8')::json ->> 'activityId' = :id
                        """)
                .param("id", activityId)
                .query(String.class)
                .list();
    }

    private String createStatus(String username, Map<String, String> parameters) throws Exception {
        var request = withToken(post("/api/v1/statuses"), token(username)).contentType(MediaType.APPLICATION_FORM_URLENCODED);
        parameters.forEach(request::param);
        var body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
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
