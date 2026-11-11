package dev.biribiri.elysium.activitypub.inbound;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.QuoteApproval;
import dev.biribiri.elysium.content.QuotePolicy;
import dev.biribiri.elysium.content.QuoteService;
import dev.biribiri.elysium.content.QuoteState;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.StatusQuote;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Quotes from other servers, and answers to them, as FEP-044f has them. The worker doesn't run, so each test runs the
 * verification job itself, and other servers are a mock. Real servers are tested by the federation lab.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://remote-quotes.example", "elysium.jobs.worker-enabled=false"})
class QuoteIntegrationTests {

    private static final String PUBLIC = "https://www.w3.org/ns/activitystreams#Public";

    @Autowired CreateHandler creates;
    @Autowired UpdateHandler updates;
    @Autowired QuoteRequestHandler requests;
    @Autowired AcceptHandler accepts;
    @Autowired RejectHandler rejects;
    @Autowired Deletions deletions;
    @Autowired QuoteVerifications verifications;
    @Autowired ActorService actors;
    @Autowired StatusService statuses;
    @Autowired QuoteService quotes;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @MockitoBean ActivityDocuments documents;
    @MockitoBean RemoteActorResolver resolver;

    private Actor alice;
    private Actor quoter;
    private Actor author;

    @BeforeEach
    void setUp() {
        alice = actors.findLocalByUsername("rqalice")
                .orElseGet(() -> actors.createLocalIfAbsent("rqalice", actor -> {}).orElseThrow());
        quoter = remote();
        author = remote();
        follow(alice, quoter);
    }

    @Test
    void readsWhatANoteQuotesFromEachPropertyInMastodonsOrder() {
        var quoted = "https://example.test/notes/1";
        var cases = List.of(
                Map.<String, Object>of("quote", quoted, "_misskey_quote", "https://example.test/notes/2"),
                Map.<String, Object>of("_misskey_quote", quoted, "quoteUrl", "https://example.test/notes/2"),
                Map.<String, Object>of("quoteUrl", quoted, "quoteUri", "https://example.test/notes/2"),
                Map.<String, Object>of("quoteUri", List.of(quoted)),
                Map.<String, Object>of("tag", List.of(Map.of("type", "Link", "href", quoted,
                        "rel", List.of("https://misskey-hub.net/ns#_misskey_quote")))),
                Map.<String, Object>of("quote", Map.of("id", quoted), "quoteAuthorization", Map.of("id", quoted + "/ok")));
        for (var properties : cases) {
            var note = new LinkedHashMap<String, Object>(Map.of("id", "https://example.test/notes/0", "type", "Note"));
            note.putAll(properties);
            var read = RemoteNote.read(json.readTree(json.writeValueAsString(note))).orElseThrow();
            assertThat(read.quote().quotedUri()).as(properties.toString()).hasToString(quoted);
            assertThat(read.quote().legacy()).as(properties.toString()).isEqualTo(!properties.containsKey("quote"));
        }
        var link = new LinkedHashMap<String, Object>(Map.of("id", "https://example.test/notes/0", "type", "Note",
                "tag", List.of(Map.of("type", "Link", "href", quoted, "rel", "alternate"))));
        assertThat(RemoteNote.read(json.readTree(json.writeValueAsString(link))).orElseThrow().quote()).isNull();
    }

    @Test
    void aLegacyQuoteOfARemoteStatusIsAccepted() {
        var quoted = storeRemote(author, "<p>original</p>");
        var quote = createFrom(quoter, Map.of("_misskey_quote", quoted.toString()));

        verify(quote);

        assertThat(quote(quote)).extracting(StatusQuote::state, StatusQuote::legacy)
                .containsExactly(QuoteState.ACCEPTED, true);
    }

    @Test
    void aQuoteWithoutApprovalStaysPending() {
        var quoted = storeRemote(author, "<p>original</p>");
        var quote = createFrom(quoter, Map.of("quote", quoted.toString()));

        verify(quote);

        assertThat(quote(quote).state()).isEqualTo(QuoteState.PENDING);
    }

    @Test
    void anAuthorsQuoteOfTheirOwnStatusNeedsNoApproval() {
        var quoted = storeRemote(quoter, "<p>mine</p>");
        var quote = createFrom(quoter, Map.of("quote", quoted.toString()));

        verify(quote);

        assertThat(quote(quote).state()).isEqualTo(QuoteState.ACCEPTED);
    }

    @Test
    void acceptsAQuoteWhoseApprovalItsAuthorsServerServes() throws Exception {
        var quoted = storeRemote(author, "<p>original</p>");
        var stamp = URI.create(author.uri() + "/quote_authorizations/" + UUID.randomUUID());
        var quote = createFrom(quoter, Map.of("quote", quoted.toString(), "quoteAuthorization", stamp.toString()));
        var quoteUri = uriOf(quote);
        serve(stamp, stamp(stamp, author.uri(), quoteUri, quoted));

        verify(quote);

        assertThat(quote(quote)).extracting(StatusQuote::state, StatusQuote::approvalUri)
                .containsExactly(QuoteState.ACCEPTED, stamp);
    }

    @Test
    void refusesAnApprovalThatDoesNotMatch() throws Exception {
        var quoted = storeRemote(author, "<p>original</p>");
        var other = storeRemote(author, "<p>another</p>");
        var cases = List.of(
                Map.<String, Object>of("attributedTo", quoter.uri().toString()),
                Map.<String, Object>of("interactionTarget", other.toString()),
                Map.<String, Object>of("interactingObject", other.toString()),
                Map.<String, Object>of("type", "Note"));
        for (var change : cases) {
            var stamp = URI.create(author.uri() + "/quote_authorizations/" + UUID.randomUUID());
            var quote = createFrom(quoter, Map.of("quote", quoted.toString(), "quoteAuthorization", stamp.toString()));
            var served = new LinkedHashMap<>(stamp(stamp, author.uri(), uriOf(quote), quoted));
            served.putAll(change);
            serve(stamp, served);

            verify(quote);

            assertThat(quote(quote).state()).as(change.toString()).isEqualTo(QuoteState.PENDING);
        }
    }

    @Test
    void neverFetchesAnApprovalFromAnotherServerThanTheQuotedAuthors() throws Exception {
        var quoted = storeRemote(author, "<p>original</p>");
        var stamp = URI.create(quoter.uri() + "/quote_authorizations/" + UUID.randomUUID());
        var quote = createFrom(quoter, Map.of("quote", quoted.toString(), "quoteAuthorization", stamp.toString()));

        verify(quote);

        Mockito.verify(documents, never()).fetch(stamp);
        assertThat(quote(quote).state()).isEqualTo(QuoteState.PENDING);
    }

    @Test
    void fetchesTheQuotedStatusButNotWhatItQuotes() throws Exception {
        var quoted = URI.create(author.uri() + "/statuses/" + UUID.randomUUID());
        var further = URI.create(author.uri() + "/statuses/" + UUID.randomUUID());
        serve(quoted, Map.of("id", quoted.toString(), "type", "Note", "attributedTo", author.uri().toString(),
                "to", List.of(PUBLIC), "content", "<p>quoted</p>", "_misskey_quote", further.toString()));
        var quote = createFrom(quoter, Map.of("_misskey_quote", quoted.toString()));

        verify(quote);

        var stored = quote(quote);
        assertThat(stored.state()).isEqualTo(QuoteState.ACCEPTED);
        var quotedId = stored.quotedStatusId();
        assertThat(quotedId).isNotNull();
        var nested = quotes.find(quotedId).orElseThrow();
        verifications.run(job(nested, false));
        Mockito.verify(documents, never()).fetch(further);
    }

    @Test
    void aQuoteThatArrivesBeforeWhatItQuotesIsLinkedLater() {
        var quoted = URI.create(author.uri() + "/statuses/" + UUID.randomUUID());
        var quote = createFrom(quoter, Map.of("quote", quoted.toString()));
        assertThat(quote(quote).quotedStatusId()).isNull();

        var stored = storeRemote(author, quoted, "<p>later</p>");

        assertThat(quote(quote).quotedStatusId()).isEqualTo(stored);
    }

    @Test
    void aLegacyQuoteOfALocalStatusFollowsItsAuthorsPolicy() {
        var open = statuses.create(alice.id(), new NewStatus("open", null, null, Visibility.PUBLIC, false, null, List.of(),
                null, QuotePolicy.PUBLIC));
        var closed = statuses.create(alice.id(), new NewStatus("closed", null, null, Visibility.PUBLIC, false, null,
                List.of(), null, QuotePolicy.NOBODY));
        var allowed = createFrom(quoter, Map.of("_misskey_quote", open.uri().toString()));
        var refused = createFrom(quoter, Map.of("_misskey_quote", closed.uri().toString()));

        verify(allowed);
        verify(refused);

        assertThat(quote(allowed).state()).isEqualTo(QuoteState.ACCEPTED);
        assertThat(quote(refused).state()).isEqualTo(QuoteState.REJECTED);
    }

    @Test
    void approvesAQuoteRequestThatThePolicyAllows() {
        var quoted = statuses.create(alice.id(), new NewStatus("open", null, null, Visibility.PUBLIC, false, null,
                List.of(), null, QuotePolicy.PUBLIC));
        var request = quoteRequest(quoter, quoted.uri(), Map.of("to", List.of(PUBLIC), "cc", List.of(alice.uri().toString())));

        requests.handle(request);
        requests.handle(request);

        var quote = statusId(URI.create(JsonPath.<String>read(request.body(), "$.instrument.id"))).orElseThrow();
        var stored = quote(quote);
        assertThat(stored.state()).isEqualTo(QuoteState.ACCEPTED);
        assertThat(stored.stampId()).isNotNull();
        var accept = answer(alice, "Accept");
        assertThat(JsonPath.<String>read(accept, "$.object.id")).isEqualTo(request.id());
        assertThat(JsonPath.<String>read(accept, "$.object.instrument")).isEqualTo(uriOf(quote).toString());
        assertThat(JsonPath.<String>read(accept, "$.result"))
                .isEqualTo(alice.uri() + "/quote_authorizations/" + stored.stampId());
        assertThat(answers(alice, "Accept")).hasSize(1);
    }

    @Test
    void rejectsAQuoteRequestThatThePolicyRefusesAndStoresNothing() {
        var quoted = statuses.create(alice.id(), new NewStatus("closed", null, null, Visibility.PUBLIC, false, null,
                List.of(), null, QuotePolicy.FOLLOWERS));
        var request = quoteRequest(quoter, quoted.uri(), Map.of("to", List.of(PUBLIC)));

        requests.handle(request);

        assertThat(statusId(URI.create(JsonPath.<String>read(request.body(), "$.instrument.id")))).isEmpty();
        assertThat(JsonPath.<String>read(answer(alice, "Reject"), "$.object.id")).isEqualTo(request.id());
    }

    @Test
    void ignoresAQuoteRequestWhoseInstrumentQuotesSomethingElse() {
        var quoted = statuses.create(alice.id(), new NewStatus("open", null, null, Visibility.PUBLIC, false, null,
                List.of(), null, QuotePolicy.PUBLIC));
        var other = statuses.create(alice.id(), new NewStatus("other", null, null, Visibility.PUBLIC, false, null,
                List.of(), null, QuotePolicy.PUBLIC));
        var request = quoteRequest(quoter, quoted.uri(), Map.of("to", List.of(PUBLIC), "quote", other.uri().toString()));

        requests.handle(request);

        assertThat(answers(alice, "Accept")).isEmpty();
        assertThat(answers(alice, "Reject")).isEmpty();
    }

    @Test
    void recordsTheAnswerToItsOwnQuoteRequest() {
        var quotedUri = storeRemote(author, "<p>theirs</p>");
        jdbc.sql("UPDATE status SET quote_automatic = '{public}' WHERE uri = :uri").param("uri", quotedUri.toString()).update();
        var quotedId = statusId(quotedUri).orElseThrow();
        var accepted = statuses.create(alice.id(), new NewStatus("one", null, null, Visibility.PUBLIC, false, null,
                List.of(), quotedId, null));
        var rejected = statuses.create(alice.id(), new NewStatus("two", null, null, Visibility.PUBLIC, false, null,
                List.of(), quotedId, null));
        var stamp = URI.create(author.uri() + "/quote_authorizations/" + UUID.randomUUID());

        // Only the quoted author can answer, and only with an approval on its own server.
        accepts.handle(answerTo(quoter, "Accept", accepted.uri() + "#quote_request", stamp));
        accepts.handle(answerTo(author, "Accept", accepted.uri() + "#quote_request",
                URI.create(quoter.uri() + "/quote_authorizations/1")));
        assertThat(quote(accepted.id()).state()).isEqualTo(QuoteState.PENDING);

        accepts.handle(answerTo(author, "Accept", accepted.uri() + "#quote_request", stamp));
        rejects.handle(answerTo(author, "Reject", rejected.uri() + "#quote_request", null));

        assertThat(quote(accepted.id())).extracting(StatusQuote::state, StatusQuote::approvalUri)
                .containsExactly(QuoteState.ACCEPTED, stamp);
        assertThat(quote(rejected.id()).state()).isEqualTo(QuoteState.REJECTED);
        assertThat(quotes.noteUpdatedAt(accepted.id())).isPresent();

        deletions.apply(stamp, quoter.uri());
        assertThat(quote(accepted.id()).state()).isEqualTo(QuoteState.ACCEPTED);
        deletions.apply(stamp, author.uri());
        assertThat(quote(accepted.id()).state()).isEqualTo(QuoteState.REVOKED);
    }

    @Test
    void anUpdateWithNoNewVersionChangesTheQuoteButKeepsNoVersion() throws Exception {
        var quoted = storeRemote(author, "<p>original</p>");
        var quote = createFrom(quoter, Map.of("quote", quoted.toString()));
        var stamp = URI.create(author.uri() + "/quote_authorizations/" + UUID.randomUUID());
        serve(stamp, stamp(stamp, author.uri(), uriOf(quote), quoted));

        var note = note(quoter, uriOf(quote), Map.of("quote", quoted.toString(), "quoteAuthorization", stamp.toString(),
                "updated", Instant.now().toString()));
        updates.handle(activity(quoter, "Update", note));

        var stored = quote(quote);
        assertThat(stored.approvalUri()).isEqualTo(stamp);
        assertThat(stored.state()).isEqualTo(QuoteState.PENDING);
        verify(quote);
        assertThat(quote(quote).state()).isEqualTo(QuoteState.ACCEPTED);
        assertThat(jdbc.sql("SELECT count(*) FROM status_edit WHERE status_id = :id").param("id", quote)
                .query(Long.class).single()).isZero();
    }

    @Test
    void readsWhoCanQuoteARemoteStatus() {
        var noteUri = URI.create(quoter.uri() + "/statuses/" + UUID.randomUUID());
        var note = note(quoter, noteUri, Map.of("interactionPolicy", Map.of("canQuote", Map.of(
                "automaticApproval", List.of(quoter.uri() + "/followers"),
                "manualApproval", List.of(PUBLIC, "https://elsewhere.test/users/one")))));
        creates.handle(activity(quoter, "Create", note));

        var id = statusId(noteUri).orElseThrow();
        assertThat(quotes.policies(List.of(id)).get(id)).isEqualTo(new QuoteApproval(
                Set.of(QuoteApproval.Audience.FOLLOWERS),
                Set.of(QuoteApproval.Audience.PUBLIC, QuoteApproval.Audience.UNSUPPORTED)));
    }

    private void verify(UUID quote) {
        verifications.run(job(quote(quote), true));
    }

    private static QuoteVerificationJob job(StatusQuote quote, boolean fetchQuoted) {
        return new QuoteVerificationJob(quote.statusId(), quote.quotedUri().toString(),
                quote.approvalUri() == null ? null : quote.approvalUri().toString(), fetchQuoted,
                Instant.now().truncatedTo(ChronoUnit.MICROS), null, null);
    }

    private StatusQuote quote(UUID statusId) {
        return quotes.find(statusId).orElseThrow();
    }

    private UUID createFrom(Actor actor, Map<String, Object> properties) {
        var uri = URI.create(actor.uri() + "/statuses/" + UUID.randomUUID());
        creates.handle(activity(actor, "Create", note(actor, uri, properties)));
        return statusId(uri).orElseThrow();
    }

    private Map<String, Object> note(Actor actor, URI id, Map<String, Object> properties) {
        var note = new LinkedHashMap<String, Object>();
        note.put("id", id.toString());
        note.put("type", "Note");
        note.put("attributedTo", actor.uri().toString());
        note.put("to", List.of(PUBLIC));
        note.put("cc", List.of(actor.uri() + "/followers"));
        note.put("content", "<p>quoting</p>");
        note.put("published", Instant.now().minusSeconds(60).toString());
        note.putAll(properties);
        return note;
    }

    private InboundActivity activity(Actor actor, String type, Map<String, Object> object) {
        var id = actor.uri() + "/activities/" + UUID.randomUUID();
        var body = Map.of("id", id, "type", type, "actor", actor.uri().toString(), "object", object);
        return new InboundActivity(id, actor.uri().toString(), type, json.writeValueAsString(body), Instant.now());
    }

    private InboundActivity quoteRequest(Actor actor, URI quoted, Map<String, Object> properties) {
        var instrument = note(actor, URI.create(actor.uri() + "/statuses/" + UUID.randomUUID()), Map.of());
        instrument.put("quote", quoted.toString());
        instrument.putAll(properties);
        var id = actor.uri() + "/quote_requests/" + UUID.randomUUID();
        var body = Map.of("id", id, "type", "QuoteRequest", "actor", actor.uri().toString(), "object", quoted.toString(),
                "instrument", instrument);
        return new InboundActivity(id, actor.uri().toString(), "QuoteRequest", json.writeValueAsString(body), Instant.now());
    }

    private InboundActivity answerTo(Actor actor, String type, String request, URI result) {
        var id = actor.uri() + "/activities/" + UUID.randomUUID();
        var body = new LinkedHashMap<String, Object>(Map.of("id", id, "type", type, "actor", actor.uri().toString(),
                "object", Map.of("id", request, "type", "QuoteRequest")));
        if (result != null) {
            body.put("result", result.toString());
        }
        return new InboundActivity(id, actor.uri().toString(), type, json.writeValueAsString(body), Instant.now());
    }

    private static Map<String, Object> stamp(URI id, URI attributedTo, URI quote, URI quoted) {
        return Map.of("id", id.toString(), "type", "QuoteAuthorization", "attributedTo", attributedTo.toString(),
                "interactingObject", quote.toString(), "interactionTarget", quoted.toString());
    }

    private URI storeRemote(Actor actor, String content) {
        var uri = URI.create(actor.uri() + "/statuses/" + UUID.randomUUID());
        storeRemote(actor, uri, content);
        return uri;
    }

    private UUID storeRemote(Actor actor, URI uri, String content) {
        return statuses.storeRemote(new RemoteStatus(uri, null, actor.id(), content, null, null, Visibility.PUBLIC, false,
                null, Instant.now())).orElseThrow();
    }

    private void serve(URI uri, Map<String, Object> document) throws DocumentFetchException {
        when(documents.fetch(uri)).thenReturn(json.readTree(json.writeValueAsString(document)));
    }

    private URI uriOf(UUID statusId) {
        return jdbc.sql("SELECT uri FROM status WHERE id = :id").param("id", statusId).query(URI.class).single();
    }

    private Optional<UUID> statusId(URI uri) {
        return jdbc.sql("SELECT id FROM status WHERE uri = :uri").param("uri", uri.toString()).query(UUID.class).optional();
    }

    private String answer(Actor actor, String type) {
        return answers(actor, type).getFirst();
    }

    private List<String> answers(Actor actor, String type) {
        return jdbc.sql("SELECT body FROM outbound_activity WHERE actor_id = :id AND activity_id LIKE :kind "
                        + "ORDER BY created_at DESC")
                .param("id", actor.id())
                .param("kind", "%#" + (type.equals("Accept") ? "accepts" : "rejects") + "/quote_requests/%")
                .query(String.class)
                .list()
                .stream()
                .filter(body -> body.contains(quoter.uri().toString()))
                .toList();
    }

    private void follow(Actor follower, Actor followed) {
        jdbc.sql("""
                        INSERT INTO follow (id, follower_id, followed_id, state, activity_uri, activity_at)
                        VALUES (:id, :follower, :followed, 'accepted', :activity, now())
                        """)
                .param("id", UUID.randomUUID())
                .param("follower", follower.id())
                .param("followed", followed.id())
                .param("activity", "https://example.test/activities/" + UUID.randomUUID())
                .update();
    }

    private Actor remote() {
        var host = "rq" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/someone");
        return actors.upsertRemote(new RemoteActorProfile(uri, "someone", host, URI.create(uri + "/inbox"), null, null,
                null, URI.create(uri + "/followers")));
    }
}
