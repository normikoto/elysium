package dev.biribiri.elysium.push;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.biribiri.elysium.TestAccounts;
import dev.biribiri.elysium.TestOAuth;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.http.BlockedTargetException;
import dev.biribiri.elysium.push.internal.P256;
import dev.biribiri.elysium.push.internal.PushHttpClient;
import dev.biribiri.elysium.push.internal.WebPushDecryption;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({TestcontainersConfiguration.class, PushIntegrationTests.Config.class})
@SpringBootTest(properties = "elysium.instance.base-url=https://push.elysium.example")
@AutoConfigureMockMvc
class PushIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String SUBSCRIPTION = "/api/v1/push/subscription";
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    private static final AtomicInteger ENDPOINTS = new AtomicInteger();

    /**
     * Logging in is slow because of Argon2, so each account logs in once, and each test gets new tokens from it.
     */
    private static final Map<String, MockHttpSession> SESSIONS = new HashMap<>();

    /**
     * Hosts the test resolver treats as having only private addresses.
     */
    private static final Set<String> BLOCKED_HOSTS = ConcurrentHashMap.newKeySet();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> answers = new ConcurrentHashMap<>();

    private record Received(String path, com.sun.net.httpserver.Headers headers, byte[] body) {
    }

    /**
     * A subscriber's side: the keys it gave, and the endpoint its push service gave it.
     */
    private record Subscriber(String endpoint, String path, KeyPair keys, byte[] authSecret) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        /**
         * Sends every endpoint's host to the test server, which only speaks HTTP, except the blocked hosts.
         */
        @Bean
        @Primary
        PushHttpClient loopbackPushClient() {
            return new PushHttpClient(new DnsResolver() {
                @Override
                public InetAddress[] resolve(String host) throws BlockedTargetException {
                    if (BLOCKED_HOSTS.contains(host)) {
                        throw new BlockedTargetException("Blocked in this test");
                    }
                    return new InetAddress[]{InetAddress.getLoopbackAddress()};
                }

                @Override
                public String resolveCanonicalHostname(String host) {
                    return host;
                }
            }, Set.of("http"), "Elysium/test");
        }
    }

    @BeforeEach
    void start() throws Exception {
        TestAccounts.makeSureAnAccountExists(actors);
        // Each test's push server stops after it, so jobs left for earlier subscriptions would only fail and wait.
        jdbc.sql("DELETE FROM push_subscription").update();
        jdbc.sql("DELETE FROM scheduled_tasks WHERE task_name = 'push'").update();
        BLOCKED_HOSTS.clear();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::answer);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    // The API

    @Test
    void subscribesShowsChangesAndUnsubscribes() throws Exception {
        var token = token("pushalice", "read write push");
        var subscriber = subscriber();

        var created = mockMvc.perform(withToken(post(SUBSCRIPTION), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(form(Map.of(
                                "subscription[endpoint]", subscriber.endpoint(),
                                "subscription[keys][p256dh]", publicKey(subscriber),
                                "subscription[keys][auth]", BASE64URL.encodeToString(subscriber.authSecret()),
                                "data[alerts][mention]", "true",
                                "data[alerts][favourite]", "1",
                                "data[alerts][reblog]", "false",
                                "data[alerts][pleroma:emoji_reaction]", "true",
                                "data[policy]", "follower"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endpoint").value(subscriber.endpoint()))
                .andExpect(jsonPath("$.standard").value(false))
                .andExpect(jsonPath("$.alerts.mention").value(true))
                .andExpect(jsonPath("$.alerts.favourite").value(true))
                .andExpect(jsonPath("$.alerts.reblog").value(false))
                .andExpect(jsonPath("$.alerts.follow").value(false))
                .andExpect(jsonPath("$.alerts['pleroma:emoji_reaction']").value(true))
                .andExpect(jsonPath("$.policy").value("follower"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        String serverKey = JsonPath.read(created, "$.server_key");
        assertThat(WebPushDecryption.base64Url(serverKey)).hasSize(P256.PUBLIC_KEY_LENGTH);

        mockMvc.perform(withToken(get(SUBSCRIPTION), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.server_key").value(serverKey));

        mockMvc.perform(withToken(put(SUBSCRIPTION), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"data": {"alerts": {"follow": true}}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.alerts.follow").value(true))
                .andExpect(jsonPath("$.alerts.mention").value(false))
                .andExpect(jsonPath("$.policy").value("follower"));
        mockMvc.perform(withToken(put(SUBSCRIPTION), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(form(Map.of("data[policy]", "all"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.follow").value(true))
                .andExpect(jsonPath("$.policy").value("all"));

        mockMvc.perform(withToken(delete(SUBSCRIPTION), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(withToken(get(SUBSCRIPTION), token)).andExpect(status().isNotFound());
        mockMvc.perform(withToken(put(SUBSCRIPTION), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\": {\"policy\": \"all\"}}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(delete(SUBSCRIPTION), token)).andExpect(status().isOk());
    }

    @Test
    void eachTokenHasOneSubscriptionThatSubscribingAgainReplaces() throws Exception {
        var token = token("pushalice", "push");
        var first = subscribe(token, subscriber(), true, Map.of("mention", true), null);
        var second = subscribe(token, subscriber(), true, Map.of("mention", true), null);

        assertThat(second).isNotEqualTo(first);
        mockMvc.perform(withToken(get(SUBSCRIPTION), token)).andExpect(jsonPath("$.id").value(second));
        assertThat(subscriptionCount(second)).isOne();
        assertThat(subscriptionCount(first)).isZero();
        mockMvc.perform(withToken(get(SUBSCRIPTION), token("pushalice", "push"))).andExpect(status().isNotFound());
    }

    @Test
    void refusesWhatCantBeSentTo() throws Exception {
        var token = token("pushalice", "push");
        var subscriber = subscriber();
        var key = publicKey(subscriber);
        var auth = BASE64URL.encodeToString(subscriber.authSecret());

        invalid(token, "ftp://push.example/x", key, auth, "all");
        invalid(token, "not a url", key, auth, "all");
        invalid(token, null, key, auth, "all");
        invalid(token, subscriber.endpoint(), BASE64URL.encodeToString(new byte[65]), auth, "all");
        invalid(token, subscriber.endpoint(), "!!!", auth, "all");
        invalid(token, subscriber.endpoint(), key, BASE64URL.encodeToString(new byte[8]), "all");
        invalid(token, subscriber.endpoint(), key, auth, "everyone");

        var unreachable = subscriber("private.example");
        BLOCKED_HOSTS.add("private.example");
        invalid(token, unreachable.endpoint(), publicKey(unreachable), BASE64URL.encodeToString(unreachable.authSecret()),
                "all");
    }

    @Test
    void needsAUserTokenWithThePushScope() throws Exception {
        var readOnly = token("pushalice", "read");
        mockMvc.perform(withToken(get(SUBSCRIPTION), readOnly)).andExpect(status().isForbidden());

        var app = TestOAuth.registerApp(mockMvc, "push");
        var body = mockMvc.perform(post("/oauth/token")
                        .param("grant_type", "client_credentials")
                        .param("scope", "push")
                        .param("client_id", app.get("client_id"))
                        .param("client_secret", app.get("client_secret")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String appToken = JsonPath.read(body, "$.access_token");
        mockMvc.perform(withToken(get(SUBSCRIPTION), appToken)).andExpect(status().isUnprocessableContent());
    }

    @Test
    void clientsAreGivenTheServerKey() throws Exception {
        var token = token("pushalice", "push");
        String serverKey = JsonPath.read(mockMvc.perform(withToken(post(SUBSCRIPTION), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(subscribeJson(subscriber(), false, Map.of(), null)))
                .andReturn().getResponse().getContentAsString(), "$.server_key");

        mockMvc.perform(get("/api/v2/instance"))
                .andExpect(jsonPath("$.configuration.vapid.public_key").value(serverKey));
        mockMvc.perform(post("/api/v1/apps")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("client_name", "Vapid")
                        .param("redirect_uris", "urn:ietf:wg:oauth:2.0:oob")
                        .param("scopes", "push"))
                .andExpect(jsonPath("$.vapid_key").value(serverKey));
        mockMvc.perform(withToken(get("/api/v1/apps/verify_credentials"), token))
                .andExpect(jsonPath("$.vapid_key").value(serverKey));
    }

    // Sending

    @Test
    void pushesAMentionInTheOlderEncoding() throws Exception {
        var token = token("pushalice", "read write push");
        var subscriber = subscriber();
        var serverKey = subscribeReturningKey(token, subscriber, false, Map.of("mention", true));

        postStatus(token("pushbob", "read write"), "Hello @pushalice, are you there?");

        await().atMost(WAIT).until(() -> receivedAt(subscriber).size() == 1);
        var request = receivedAt(subscriber).getFirst();
        assertThat(request.headers().getFirst("Content-Encoding")).isEqualTo("aesgcm");
        assertThat(request.headers().getFirst("TTL")).isEqualTo("172800");
        assertThat(request.headers().getFirst("Authorization")).startsWith("WebPush ");
        var salt = parameter(request.headers().getFirst("Encryption"), "salt");
        var cryptoKey = request.headers().getFirst("Crypto-Key");
        assertThat(parameter(cryptoKey, "p256ecdsa")).isEqualTo(serverKey);
        var message = new String(WebPushDecryption.aesgcm(request.body(), WebPushDecryption.base64Url(salt),
                WebPushDecryption.base64Url(parameter(cryptoKey, "dh")), subscriber.keys(), subscriber.authSecret()),
                StandardCharsets.UTF_8);

        assertThat((String) JsonPath.read(message, "$.notification_type")).isEqualTo("mention");
        assertThat((String) JsonPath.read(message, "$.access_token")).isEqualTo(token);
        assertThat((String) JsonPath.read(message, "$.title")).isEqualTo("You were mentioned by pushbob");
        assertThat((String) JsonPath.read(message, "$.body")).isEqualTo("Hello @pushalice, are you there?");
        String notificationId = JsonPath.read(message, "$.notification_id");
        mockMvc.perform(withToken(get("/api/v1/notifications/" + notificationId), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("mention"));
    }

    @Test
    void pushesAnEmojiReactionInTheStandardEncoding() throws Exception {
        var alice = token("pushalice", "read write push");
        var subscriber = subscriber();
        var serverKey = subscribeReturningKey(alice, subscriber, true, Map.of("pleroma:emoji_reaction", true));
        var statusId = postStatus(alice, "Fruit opinions");

        mockMvc.perform(withToken(put("/api/v1/pleroma/statuses/" + statusId + "/reactions/🍉"),
                        token("pushbob", "read write")))
                .andExpect(status().isOk());

        await().atMost(WAIT).until(() -> receivedAt(subscriber).size() == 1);
        var request = receivedAt(subscriber).getFirst();
        assertThat(request.headers().getFirst("Content-Encoding")).isEqualTo("aes128gcm");
        assertThat(request.headers().getFirst("Authorization")).startsWith("vapid t=").endsWith(", k=" + serverKey);
        var message = new String(WebPushDecryption.aes128gcm(request.body(), subscriber.keys(), subscriber.authSecret()),
                StandardCharsets.UTF_8);
        assertThat((String) JsonPath.read(message, "$.notification_type")).isEqualTo("pleroma:emoji_reaction");
        assertThat((String) JsonPath.read(message, "$.title")).isEqualTo("pushbob reacted with 🍉 to your post");
        assertThat((String) JsonPath.read(message, "$.body")).isEqualTo("Fruit opinions");
    }

    @Test
    void onlyPushesTheChosenTypes() throws Exception {
        var alice = token("pushalice", "read write push");
        var subscriber = subscriber();
        subscribe(alice, subscriber, true, Map.of("favourite", true, "mention", false), null);
        var bob = token("pushbob", "read write");

        postStatus(bob, "@pushalice not this one");
        settle();
        var statusId = postStatus(alice, "Like this");
        mockMvc.perform(withToken(post("/api/v1/statuses/" + statusId + "/favourite"), bob)).andExpect(status().isOk());

        await().atMost(WAIT).until(() -> receivedAt(subscriber).size() == 1);
        settle();
        assertThat(receivedAt(subscriber)).hasSize(1);
        assertThat(notificationType(receivedAt(subscriber).getFirst(), subscriber)).isEqualTo("favourite");
    }

    @Test
    void thePolicyLimitsWhoseNotificationsArePushed() throws Exception {
        var alice = token("pushalice", "read write push");
        var subscriber = subscriber();
        subscribe(alice, subscriber, true, Map.of("mention", true), "followed");

        postStatus(token("pushbob", "read write"), "@pushalice you don't follow me");

        settle();
        assertThat(receivedAt(subscriber)).isEmpty();
    }

    @Test
    void aSubscriptionThePushServiceHasForgottenIsRemoved() throws Exception {
        var alice = token("pushalice", "read write push");
        var subscriber = subscriber();
        var id = subscribe(alice, subscriber, true, Map.of("mention", true), null);
        answers.put(subscriber.path(), 410);

        postStatus(token("pushbob", "read write"), "@pushalice gone");

        await().atMost(WAIT).until(() -> subscriptionCount(id) == 0);
        assertThat(receivedAt(subscriber)).hasSize(1);
    }

    @Test
    void aSubscriptionWhoseHostIsNoLongerPublicIsRemoved() throws Exception {
        var alice = token("pushalice", "read write push");
        var subscriber = subscriber("moved.example");
        var id = subscribe(alice, subscriber, true, Map.of("mention", true), null);
        BLOCKED_HOSTS.add("moved.example");

        postStatus(token("pushbob", "read write"), "@pushalice moved");

        await().atMost(WAIT).until(() -> subscriptionCount(id) == 0);
        settle();
        assertThat(receivedAt(subscriber)).isEmpty();
    }

    @Test
    void aRevokedTokensSubscriptionStops() throws Exception {
        var app = TestOAuth.registerApp(mockMvc, "read write push");
        var alice = TestOAuth.userToken(mockMvc, app, session("pushalice"), "read write push");
        var subscriber = subscriber();
        var id = subscribe(alice, subscriber, true, Map.of("mention", true), null);
        mockMvc.perform(post("/oauth/revoke")
                        .param("client_id", app.get("client_id"))
                        .param("client_secret", app.get("client_secret"))
                        .param("token", alice))
                .andExpect(status().isOk());

        postStatus(token("pushbob", "read write"), "@pushalice still there?");

        await().atMost(WAIT).until(() -> subscriptionCount(id) == 0);
        assertThat(receivedAt(subscriber)).isEmpty();
    }

    @Test
    void revokingTheAppDeletesItsSubscription() throws Exception {
        var app = TestOAuth.registerApp(mockMvc, "push");
        var alice = TestOAuth.userToken(mockMvc, app, session("pushalice"), "push");
        var id = subscribe(alice, subscriber(), true, Map.of("mention", true), null);

        jdbc.sql("DELETE FROM oauth2_authorization WHERE registered_client_id = (SELECT id FROM oauth2_registered_client WHERE client_id = :clientId)")
                .param("clientId", app.get("client_id"))
                .update();

        assertThat(subscriptionCount(id)).isZero();
    }

    // Helpers

    private void answer(HttpExchange exchange) throws IOException {
        var body = exchange.getRequestBody().readAllBytes();
        var path = exchange.getRequestURI().getPath();
        received.add(new Received(path, exchange.getRequestHeaders(), body));
        exchange.sendResponseHeaders(answers.getOrDefault(path, 201), -1);
        exchange.close();
    }

    private List<Received> receivedAt(Subscriber subscriber) {
        return received.stream().filter(request -> request.path().equals(subscriber.path())).toList();
    }

    private Subscriber subscriber() {
        return subscriber("push.example");
    }

    private Subscriber subscriber(String host) {
        var path = "/push/" + ENDPOINTS.incrementAndGet();
        var secret = new byte[16];
        new SecureRandom().nextBytes(secret);
        return new Subscriber("http://" + host + ":" + server.getAddress().getPort() + path, path, P256.generate(), secret);
    }

    private static String publicKey(Subscriber subscriber) {
        return BASE64URL.encodeToString(P256.encode((ECPublicKey) subscriber.keys().getPublic()));
    }

    private String subscribe(String token, Subscriber subscriber, boolean standard, Map<String, Boolean> alerts,
                             String policy) throws Exception {
        return JsonPath.read(subscribing(token, subscriber, standard, alerts, policy), "$.id");
    }

    private String subscribeReturningKey(String token, Subscriber subscriber, boolean standard,
                                         Map<String, Boolean> alerts) throws Exception {
        return JsonPath.read(subscribing(token, subscriber, standard, alerts, null), "$.server_key");
    }

    private String subscribing(String token, Subscriber subscriber, boolean standard, Map<String, Boolean> alerts,
                               String policy) throws Exception {
        return mockMvc.perform(withToken(post(SUBSCRIPTION), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(subscribeJson(subscriber, standard, alerts, policy)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static String subscribeJson(Subscriber subscriber, boolean standard, Map<String, Boolean> alerts,
                                        String policy) {
        var alertJson = new StringBuilder();
        alerts.forEach((type, on) -> alertJson.append(alertJson.isEmpty() ? "" : ",")
                .append('"').append(type).append("\":").append(on));
        return """
                {"subscription": {"endpoint": "%s", "keys": {"p256dh": "%s", "auth": "%s"}, "standard": %s},
                 "data": {"alerts": {%s}%s}}""".formatted(subscriber.endpoint(), publicKey(subscriber),
                BASE64URL.encodeToString(subscriber.authSecret()), standard, alertJson,
                policy == null ? "" : ", \"policy\": \"" + policy + "\"");
    }

    private void invalid(String token, String endpoint, String p256dh, String auth, String policy) throws Exception {
        var fields = new HashMap<String, String>();
        if (endpoint != null) fields.put("subscription[endpoint]", endpoint);
        fields.put("subscription[keys][p256dh]", p256dh);
        fields.put("subscription[keys][auth]", auth);
        fields.put("data[policy]", policy);
        mockMvc.perform(withToken(post(SUBSCRIPTION), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(form(fields)))
                .andExpect(status().isUnprocessableContent());
    }

    private String notificationType(Received request, Subscriber subscriber) throws Exception {
        var message = new String(WebPushDecryption.aes128gcm(request.body(), subscriber.keys(), subscriber.authSecret()),
                StandardCharsets.UTF_8);
        return JsonPath.read(message, "$.notification_type");
    }

    private String postStatus(String token, String text) throws Exception {
        var body = mockMvc.perform(withToken(post("/api/v1/statuses"), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", text))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /**
     * Waits until every listener has run and every push job has finished.
     */
    private void settle() {
        await().atMost(WAIT).until(() -> jdbc.sql("SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0
                && jdbc.sql("SELECT count(*) FROM scheduled_tasks WHERE task_name = 'push'")
                .query(Long.class).single() == 0);
    }

    private long subscriptionCount(String id) {
        return jdbc.sql("SELECT count(*) FROM push_subscription WHERE id = CAST(:id AS uuid)")
                .param("id", id).query(Long.class).single();
    }

    private static String parameter(String header, String name) {
        for (var part : header.split("[;,]")) {
            var pair = part.strip().split("=", 2);
            if (pair[0].equals(name)) return pair[1];
        }
        throw new AssertionError(name + " isn't in " + header);
    }

    private static String form(Map<String, String> fields) {
        var form = new StringBuilder();
        fields.forEach((name, value) -> form.append(form.isEmpty() ? "" : "&")
                .append(URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return form.toString();
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String token(String username, String scope) throws Exception {
        return TestOAuth.userToken(mockMvc, TestOAuth.registerApp(mockMvc, scope), session(username), scope);
    }

    private MockHttpSession session(String username) throws Exception {
        var session = SESSIONS.get(username);
        if (session != null) return session;
        if (actors.findLocalByUsername(username).isEmpty()) {
            mockMvc.perform(post("/signup").with(csrf())
                            .param("invite", TestAccounts.invite(jdbc))
                            .param("username", username)
                            .param("password", PASSWORD)
                            .param("confirmPassword", PASSWORD))
                    .andExpect(status().is3xxRedirection());
        }
        session = TestOAuth.logIn(mockMvc, username, PASSWORD);
        SESSIONS.put(username, session);
        return session;
    }
}
