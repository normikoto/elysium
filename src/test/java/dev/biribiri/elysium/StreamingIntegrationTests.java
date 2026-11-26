package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.IdPage;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.api.streaming.StreamConnection;
import dev.biribiri.elysium.api.streaming.StreamRegistry;
import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.PostFilter;
import dev.biribiri.elysium.content.PostFilterCache;
import dev.biribiri.elysium.content.PostFilterChange;
import dev.biribiri.elysium.content.PostFilters;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.RemoteStatuses;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusEdit;
import dev.biribiri.elysium.content.StatusOrigin;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.TagFilter;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.graph.AccountLists;
import dev.biribiri.elysium.graph.BlockService;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.graph.TagFollows;
import dev.biribiri.elysium.timeline.TimelineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"elysium.instance.base-url=https://streaming.example", "elysium.jobs.worker-enabled=false"})
@AutoConfigureMockMvc
class StreamingIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";
    private static final Map<String, String> TOKENS = new HashMap<>();
    /** Long enough for an event that would arrive to have arrived, once its listeners have finished. */
    private static final Duration QUIET = Duration.ofMillis(500);

    @LocalServerPort int port;
    @Autowired MockMvc mockMvc;
    @Autowired ActorService actors;
    @Autowired StatusService statuses;
    @Autowired RemoteStatuses remoteStatuses;
    @Autowired FollowService follows;
    @Autowired BlockService blocks;
    @Autowired LikeService likes;
    @Autowired TimelineService timelines;
    @Autowired StreamRegistry registry;
    @Autowired JdbcClient jdbc;
    @Autowired PostFilters filters;
    @Autowired PostFilterCache filterCache;
    @Autowired AccountLists lists;
    @Autowired TagFollows tagFollows;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Client> clients = new ArrayList<>();
    private Actor alice;
    private Actor bob;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() throws Exception {
        settled();
        jdbc.sql("DELETE FROM status").update();
        jdbc.sql("DELETE FROM follow").update();
        jdbc.sql("DELETE FROM tag_follow").update();
        jdbc.sql("DELETE FROM account_block").update();
        jdbc.sql("DELETE FROM notification").update();
        jdbc.sql("DELETE FROM conversation").update();
        jdbc.sql("DELETE FROM post_filter").update();
        jdbc.sql("DELETE FROM account_list").update();
        filterCache.clear();
        TestAccounts.makeSureAnAccountExists(actors);
        aliceToken = token("streamalice");
        bobToken = token("streambob");
        alice = actors.findLocalByUsername("streamalice").orElseThrow();
        bob = actors.findLocalByUsername("streambob").orElseThrow();        TestAccounts.acceptPrivateMentions(jdbc);
    }

    @AfterEach
    void closeClients() {
        clients.forEach(Client::close);
        await().atMost(Duration.ofSeconds(10)).until(() -> registry.connections().isEmpty());
    }

    @Test
    void theUserStreamCarriesTheHomeTimelineAndItsDeletions() throws Exception {
        follows.follow(alice, bob);
        var stream = webSocket("?stream=user", aliceToken);

        var status = post(bob, "hello followers", Visibility.PUBLIC);
        var update = stream.next(event("update"));
        assertThat(JsonPath.<List<String>>read(update, "$.stream")).containsExactly("user");
        String payload = JsonPath.read(update, "$.payload");
        assertThat(JsonPath.<String>read(payload, "$.id")).isEqualTo(status.id().toString());
        assertThat(JsonPath.<String>read(payload, "$.account.id")).isEqualTo(bob.id().toString());

        statuses.delete(status.id(), bob.id());
        var delete = stream.next(event("delete"));
        assertThat(JsonPath.<String>read(delete, "$.payload")).isEqualTo(status.id().toString());
    }

    @Test
    void filtersHideStatusesFromTheStreamsOfTheirContextsAndAnEditThatTheyHideDeletesOne() throws Exception {
        follows.follow(alice, bob);
        filters.create(alice.id(), new PostFilterChange("Hidden", Set.of(PostFilter.Context.HOME),
                PostFilter.Action.HIDE, null, List.of(new PostFilterChange.KeywordEdit(null, "secret", null, false))));
        settled();
        var home = webSocket("?stream=user", aliceToken);
        var everything = webSocket("?stream=public:local", aliceToken);

        var hidden = post(bob, "a secret", Visibility.PUBLIC);
        var message = everything.next(event("update"));
        String payload = JsonPath.read(message, "$.payload");
        assertThat(JsonPath.<String>read(payload, "$.id")).isEqualTo(hidden.id().toString());
        assertThat(JsonPath.<List<String>>read(payload, "$.filtered[*].keyword_matches[*]")).containsExactly("secret");
        home.expectNothing();

        var status = post(bob, "nothing yet", Visibility.PUBLIC);
        var shown = home.next(event("update"));
        assertThat(JsonPath.<String>read(JsonPath.<String>read(shown, "$.payload"), "$.id"))
                .isEqualTo(status.id().toString());

        statuses.edit(status.id(), bob.id(), new StatusEdit("now a secret", null, null, null));
        var delete = home.next(event("delete"));
        assertThat(JsonPath.<String>read(delete, "$.payload")).isEqualTo(status.id().toString());
    }

    @Test
    void changingFiltersIsStreamedToTheirOwner() throws Exception {
        var aliceStream = webSocket("?stream=user", aliceToken);
        var bobStream = webSocket("?stream=user", bobToken);
        filters.create(alice.id(), new PostFilterChange("New", Set.of(PostFilter.Context.HOME), null, null, List.of()));

        var message = aliceStream.next(event("filters_changed"));
        assertThat(JsonPath.<List<String>>read(message, "$.stream")).containsExactly("user");
        assertThat(message).doesNotContain("payload");
        bobStream.expectNothing();
    }

    @Test
    void aListStreamCarriesItsMembersAndOnlyForItsOwner() throws Exception {
        follows.follow(alice, bob);
        var list = lists.create(alice.id(), "Friends", null, true);
        assertThat(lists.addMembers(alice.id(), list.id(), List.of(bob.id()))).isTrue();
        var stream = webSocket("?stream=list&list=" + list.id(), aliceToken);
        var home = webSocket("?stream=user", aliceToken);

        var status = post(bob, "to the list", Visibility.PUBLIC);
        var update = stream.next(event("update"));
        assertThat(JsonPath.<List<String>>read(update, "$.stream")).containsExactly("list", list.id().toString());
        assertThat(JsonPath.<String>read((String) JsonPath.read(update, "$.payload"), "$.id"))
                .isEqualTo(status.id().toString());
        // An exclusive list's members are left out of home.
        home.expectNothing();

        var refused = webSocket("", bobToken);
        refused.send("{\"type\":\"subscribe\",\"stream\":\"list\",\"list\":\"" + list.id() + "\"}");
        var error = refused.next(found -> true);
        assertThat(JsonPath.<String>read(error, "$.error")).isEqualTo("Not authorized to stream this list");
        assertThat(JsonPath.<Integer>read(error, "$.status")).isEqualTo(401);
        refused.send("{\"type\":\"subscribe\",\"stream\":\"list\"}");
        assertThat(JsonPath.<String>read(refused.next(found -> true), "$.error"))
                .isEqualTo("Missing list name parameter");
        assertThat(get("/api/v1/streaming/list?list=" + list.id() + "&access_token=" + bobToken).statusCode())
                .isEqualTo(401);
    }

    @Test
    void theUserStreamCarriesFollowedTags() throws Exception {
        tagFollows.follow(alice.id(), "cats");
        var stream = webSocket("?stream=user", aliceToken);

        var status = post(bob, "#Cats", Visibility.PUBLIC);
        var update = stream.next(event("update"));
        assertThat(JsonPath.<String>read(JsonPath.<String>read(update, "$.payload"), "$.id"))
                .isEqualTo(status.id().toString());
        post(bob, "#cats quietly", Visibility.UNLISTED);
        stream.expectNothing();
    }

    @Test
    void notificationsAreStreamedToTheirRecipient() throws Exception {
        var aliceStream = webSocket("?stream=user:notification", aliceToken);
        var bobStream = webSocket("?stream=user:notification", bobToken);
        var status = post(alice, "like this", Visibility.PUBLIC);
        likes.favourite(status.id(), bob.id());

        var message = aliceStream.next(event("notification"));
        String payload = JsonPath.read(message, "$.payload");
        assertThat(JsonPath.<String>read(payload, "$.type")).isEqualTo("favourite");
        assertThat(JsonPath.<String>read(payload, "$.account.id")).isEqualTo(bob.id().toString());
        bobStream.expectNothing();
    }

    @Test
    void publicStreamsOnlyCarryWhatTheirTimelinesList() throws Exception {
        var anonymous = webSocket("?stream=public:local", null);
        var blocking = webSocket("?stream=public:local", aliceToken);
        blocks.block(alice, bob);

        post(bob, "quietly", Visibility.UNLISTED);
        var listed = post(bob, "loudly", Visibility.PUBLIC);
        var message = anonymous.next(event("update"));
        assertThat(JsonPath.<String>read((String) JsonPath.read(message, "$.payload"), "$.id"))
                .isEqualTo(listed.id().toString());
        anonymous.expectNothing();
        blocking.expectNothing();
    }

    @Test
    void remoteStatusesReachPublicAndRemoteStreamsButNotLocalOnes() throws Exception {
        var everything = webSocket("?stream=public", null);
        var local = webSocket("?stream=public:local", null);
        var remote = remote();
        var uri = URI.create(remote.uri() + "/notes/" + UUID.randomUUID());
        var id = remoteStatuses.storeRemote(new RemoteStatus(uri, null, remote.id(), "<p>from afar</p>", null, null,
                Visibility.PUBLIC, false, null, Instant.now(), null, Map.of())).orElseThrow();

        everything.next(event("update"));
        remoteStatuses.deleteRemote(uri, remote.uri());
        var delete = everything.next(event("delete"));
        assertThat(JsonPath.<String>read(delete, "$.payload")).isEqualTo(id.toString());
        // Every stream that carries statuses hears about a deletion, as on Mastodon.
        local.next(event("delete"));
        local.expectNothing();
    }

    @Test
    void directMessagesUpdateConversations() throws Exception {
        var stream = webSocket("?stream=direct", bobToken);
        post(alice, "@streambob just us", Visibility.DIRECT);
        var message = stream.next(event("conversation"));
        assertThat(JsonPath.<List<String>>read((String) JsonPath.read(message, "$.payload"), "$.accounts[*].id"))
                .containsExactly(alice.id().toString());
    }

    @Test
    void hashtagSubscriptionsCanBeAddedAndRemoved() throws Exception {
        var stream = webSocket("", null);
        stream.send("{\"type\":\"subscribe\",\"stream\":\"hashtag\",\"tag\":\"Cats\"}");
        await().atMost(Duration.ofSeconds(10)).until(() -> !registry.connections().isEmpty());

        var tagged = post(alice, "look #cats", Visibility.PUBLIC);
        var message = stream.next(event("update"));
        assertThat(JsonPath.<List<String>>read(message, "$.stream")).containsExactly("hashtag", "cats");
        assertThat(JsonPath.<String>read((String) JsonPath.read(message, "$.payload"), "$.id"))
                .isEqualTo(tagged.id().toString());

        stream.send("{\"type\":\"unsubscribe\",\"stream\":\"hashtag\",\"tag\":\"cats\"}");
        await().atMost(Duration.ofSeconds(10)).until(() -> registry.connections().isEmpty());
        post(alice, "more #cats", Visibility.PUBLIC);
        stream.expectNothing();
    }

    @Test
    void refusedSubscriptionsGetErrorsAndKeepTheConnection() throws Exception {
        var stream = webSocket("", null);
        stream.send("{\"type\":\"subscribe\",\"stream\":\"user\"}");
        var refused = stream.next(found -> true);
        assertThat(JsonPath.<Integer>read(refused, "$.status")).isEqualTo(401);

        stream.send("{\"type\":\"subscribe\",\"stream\":\"lists\"}");
        var unknown = stream.next(found -> true);
        assertThat(JsonPath.<String>read(unknown, "$.error")).isEqualTo("Unknown stream type");
        assertThat(JsonPath.<Integer>read(unknown, "$.status")).isEqualTo(400);

        stream.send("not json");
        assertThat(JsonPath.<Integer>read(stream.next(found -> true), "$.status")).isEqualTo(400);

        stream.send("{\"type\":\"subscribe\",\"stream\":\"public\"}");
        await().atMost(Duration.ofSeconds(10)).until(() -> !registry.connections().isEmpty());
    }

    @Test
    void aStreamInTheUrlThatCantBeSubscribedToClosesTheConnection() throws Exception {
        var stream = webSocket("?stream=user", null);
        assertThat(JsonPath.<Integer>read(stream.next(found -> true), "$.status")).isEqualTo(401);
        stream.closed.get(10, TimeUnit.SECONDS);
    }

    @Test
    void theTokenCanBeInTheQueryOrTheProtocol() throws Exception {
        follows.follow(alice, bob);
        var query = connect("?stream=user&access_token=" + aliceToken, Map.of(), Optional.empty());
        var protocol = connect("?stream=user", Map.of(), Optional.of(aliceToken));
        assertThat(protocol.socket.getSubprotocol()).isEqualTo(aliceToken);
        await().atMost(Duration.ofSeconds(10)).until(() -> registry.connections().size() == 2);

        post(bob, "both of you", Visibility.PUBLIC);
        query.next(event("update"));
        protocol.next(event("update"));
    }

    @Test
    void anInvalidTokenIsRefused() {
        assertThatThrownBy(() -> connect("?stream=public&access_token=wrong", Map.of(), Optional.empty()))
                .cause()
                .isInstanceOfSatisfying(WebSocketHandshakeException.class,
                        e -> assertThat(e.getResponse().statusCode()).isEqualTo(401));
    }

    @Test
    void serverSentEventsCarryTheSameEvents() throws Exception {
        var events = new LinkedBlockingQueue<String>();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/v1/streaming/public/local?access_token=" + aliceToken)).build();
        var response = http.sendAsync(request, HttpResponse.BodyHandlers.ofLines());
        var lines = response.get(10, TimeUnit.SECONDS);
        assertThat(lines.statusCode()).isEqualTo(200);
        assertThat(lines.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/event-stream"));
        var reader = Thread.ofVirtual().start(() -> lines.body().forEach(events::add));
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> !registry.connections().isEmpty());
            var status = post(bob, "evented", Visibility.PUBLIC);
            await().atMost(Duration.ofSeconds(10)).until(() -> events.contains("event:update"));
            var data = events.stream().filter(line -> line.startsWith("data:")).findFirst().orElseThrow();
            assertThat(JsonPath.<String>read(data.substring("data:".length()), "$.id"))
                    .isEqualTo(status.id().toString());
        } finally {
            reader.interrupt();
            response.cancel(true);
            registry.connections().forEach(StreamConnection::closeNow);
        }
    }

    @Test
    void eventStreamsCheckTheirParameters() throws Exception {
        assertThat(get("/api/v1/streaming/health").body()).isEqualTo("OK");
        assertThat(get("/api/v1/streaming/hashtag").statusCode()).isEqualTo(400);
        // As an EventSource asks, which a JSON error still has to be written for.
        var fromEventSource = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/v1/streaming/hashtag")).header("Accept", "text/event-stream").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(fromEventSource.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(fromEventSource.body(), "$.error")).isEqualTo("Missing tag name parameter");
        assertThat(get("/api/v1/streaming/user").statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/streaming/list?access_token=" + aliceToken).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/streaming/list?list=1&access_token=" + aliceToken).statusCode()).isEqualTo(401);
    }

    @Test
    void theStreamChecksAgreeWithTheTimelines() {
        var carol = actors.findLocalByUsername("streamcarol")
                .orElseGet(() -> actors.createLocalIfAbsent("streamcarol", actor -> {
                }).orElseThrow());
        follows.follow(alice, bob);
        var parent = post(carol, "not followed", Visibility.PUBLIC);
        var reply = statuses.create(bob.id(), new NewStatus("@streamcarol reply", null, null, Visibility.PUBLIC, false,
                parent.id()));
        var direct = post(bob, "@streamalice psst", Visibility.DIRECT);
        var tagged = post(bob, "#dogs", Visibility.PUBLIC);
        var cats = post(carol, "#cats", Visibility.PUBLIC);
        tagFollows.follow(alice.id(), "cats");

        var home = timelines.homeViewers(reply.id(), List.of(alice.id()));
        assertThat(home).isEmpty();
        assertThat(timelines.homeViewers(direct.id(), List.of(alice.id(), carol.id()))).containsExactly(alice.id());
        assertThat(timelines.inPublic(direct.id(), StatusOrigin.ANY, false, Optional.empty())).isFalse();
        assertThat(timelines.inPublic(reply.id(), StatusOrigin.ANY, false, Optional.empty())).isFalse();
        assertThat(timelines.inTag(tagged.id(), TagFilter.of("dogs"), StatusOrigin.LOCAL, false, Optional.empty()))
                .isTrue();
        assertThat(timelines.inTag(tagged.id(), TagFilter.of("dogs"), StatusOrigin.REMOTE, false, Optional.empty()))
                .isFalse();

        follows.follow(carol, bob);
        var everyone = List.of(alice.id(), bob.id(), carol.id());
        for (var status : List.of(parent, reply, direct, tagged, cats)) {
            var listing = everyone.stream().filter(viewer -> timelines.homeTimeline(viewer,
                    new IdPage(40, null, null, null)).stream().anyMatch(found -> found.id().equals(status.id()))).toList();
            assertThat(timelines.homeViewers(status.id(), everyone)).as(status.text())
                    .containsExactlyInAnyOrderElementsOf(listing);
        }

        blocks.block(alice, bob);
        assertThat(timelines.homeViewers(tagged.id(), List.of(alice.id()))).isEmpty();
        assertThat(timelines.inPublic(tagged.id(), StatusOrigin.ANY, false, Optional.of(alice.id()))).isFalse();
        assertThat(timelines.inPublic(tagged.id(), StatusOrigin.ANY, false, Optional.of(carol.id()))).isTrue();
    }

    private static Predicate<String> event(String name) {
        return message -> message.contains("\"event\":\"" + name + "\"");
    }

    private Status post(Actor author, String text, Visibility visibility) {
        var status = statuses.create(author.id(), new NewStatus(text, null, null, visibility, false, null));
        settled();
        return status;
    }

    private Actor remote() {
        var uri = URI.create("https://far.example/users/" + UUID.randomUUID());
        return actors.upsertRemote(new RemoteActorProfile(uri, "remote", "far.example", URI.create(uri + "/inbox"),
                null, null, null));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Connects with the token in the {@code Authorization} header, and waits until the stream in the query, if any, has been
     * subscribed to, which the server only does after the handshake has been answered.
     */
    private Client webSocket(String query, String token) throws Exception {
        var before = registry.connections().size();
        var client = connect(query, token == null ? Map.of() : Map.of("Authorization", "Bearer " + token),
                Optional.empty());
        if (query.contains("stream=")) {
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> registry.connections().size() > before || !client.messages.isEmpty());
        }
        return client;
    }

    private Client connect(String query, Map<String, String> headers, Optional<String> protocol) {
        var client = new Client();
        var builder = http.newWebSocketBuilder();
        headers.forEach(builder::header);
        protocol.ifPresent(builder::subprotocols);
        client.socket = builder.buildAsync(URI.create("ws://localhost:" + port + "/api/v1/streaming" + query), client)
                .join();
        clients.add(client);
        return client;
    }

    private String token(String username) throws Exception {
        var cached = TOKENS.get(username);
        if (cached != null) {
            return cached;
        }
        mockMvc.perform(MockMvcRequestBuilders.post("/signup").with(csrf())
                        .param("invite", TestAccounts.invite(jdbc))
                        .param("username", username)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(status().is3xxRedirection());
        var token = TestOAuth.userToken(mockMvc, username, PASSWORD, "read write");
        TOKENS.put(username, token);
        return token;
    }

    private void settled() {
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL").query(Long.class).single() == 0);
    }

    private static final class Client implements WebSocket.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();
        WebSocket socket;

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(null);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.complete(null);
        }

        void send(String text) {
            socket.sendText(text, true).join();
        }

        /**
         * Waits for the next message that matches, skipping others.
         */
        String next(Predicate<String> matching) throws InterruptedException {
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                var message = messages.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                if (message != null && matching.test(message)) {
                    return message;
                }
            }
            throw new AssertionError("No matching message arrived");
        }

        void expectNothing() throws InterruptedException {
            assertThat(messages.poll(QUIET.toMillis(), TimeUnit.MILLISECONDS)).isNull();
        }

        void close() {
            if (socket != null && !socket.isOutputClosed()) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "").exceptionally(_ -> null);
            }
            if (socket != null) {
                socket.abort();
            }
        }
    }
}
