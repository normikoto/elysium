package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.TestAccounts;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.ActorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Logs in through a real server, because the session cookie's attributes differ under MockMvc. There, Spring Boot treats the
 * application as a WAR, and copies them from a mock servlet context that doesn't turn on {@code HttpOnly}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"server.servlet.session.timeout=2h", "elysium.registration.mode=open"})
class LoginSessionCookieIntegrationTests {

    private static final String PASSWORD = "a long enough password";
    private static final Pattern CLIENT_ID = Pattern.compile("\"client_id\":\"([^\"]+)\"");
    private static final Pattern CSRF_TOKEN = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private ActorService actors;

    @Autowired
    private LocalAccounts accounts;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SessionRepository<? extends Session> sessions;

    @Test
    void theCookieCantBeReadByScriptsAndLoggingInAndResettingWork() throws Exception {
        TestAccounts.makeSureAnAccountExists(actors);
        var username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 25);
        accounts.createAccount(username, PASSWORD).orElseThrow();

        // This saves the page to return to after logging in.
        var page = HttpRequest.newBuilder(uri("/settings/delete-account")).header("Accept", "text/html").GET();
        var denied = send(page, null);
        assertThat(denied.statusCode()).isEqualTo(302);
        var savedPage = setCookie(denied, "SAVED-PAGE=");
        assertThat(savedPage).contains("; Path=/").contains("; HttpOnly").contains("; SameSite=Lax");

        var loginPage = send(HttpRequest.newBuilder(uri("/login")).GET(), value(savedPage));
        assertThat(loginPage.headers().allValues("Set-Cookie")).noneMatch(cookie -> cookie.startsWith("SESSION="));
        var csrfCookie = setCookie(loginPage, "XSRF-TOKEN=");
        assertThat(csrfCookie).contains("; Path=/").contains("; HttpOnly").contains("; SameSite=Lax");
        var token = CSRF_TOKEN.matcher(loginPage.body());
        assertThat(token.find()).isTrue();

        var login = logIn(username, token.group(1), value(csrfCookie) + "; " + value(savedPage));
        assertThat(login.statusCode()).isEqualTo(302);
        assertThat(login.headers().firstValue("Location").orElseThrow()).endsWith("/settings/delete-account");
        var loggedIn = setCookie(login, "SESSION=");
        assertThat(loggedIn).contains("; HttpOnly").contains("; SameSite=Lax");

        var actorId = actors.findLocalByUsername(username).orElseThrow().id();
        assertThat(jdbc.sql("SELECT max_inactive_interval FROM spring_session WHERE principal_name = :id")
                .param("id", actorId.toString())
                .query(Integer.class)
                .single()).isEqualTo(7200);

        assertThat(send(page, value(loggedIn)).statusCode()).isEqualTo(200);
        assertOnlyTheHashIsStored(actorId, value(loggedIn), page);

        accounts.resetPassword(actorId, "another long enough password");

        var afterReset = send(page, value(loggedIn));
        assertThat(afterReset.statusCode()).isEqualTo(302);
        assertThat(afterReset.headers().firstValue("Location").orElseThrow()).endsWith("/login");
    }

    /**
     * A request that read the session before the login finished writes the session back afterward, with the ID it knows. Someone who
     * planted that ID could keep sending requests to get there, so that ID mustn't become logged in.
     */
    @Test
    void aRequestOverlappingALoginCantBringBackTheOldId() throws Exception {
        TestAccounts.makeSureAnAccountExists(actors);
        var username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 25);
        accounts.createAccount(username, PASSWORD).orElseThrow();

        // Visitors who aren't logged in don't get sessions, but someone can still plant one of their own, such as one
        // that's waiting for a two-factor code.
        var plantedId = newSession(sessions);
        var plantedCookie = "SESSION=" + Base64.getEncoder().encodeToString(plantedId.getBytes(StandardCharsets.UTF_8));
        var loginPage = send(HttpRequest.newBuilder(uri("/login")).GET(), plantedCookie);
        var csrfCookie = value(setCookie(loginPage, "XSRF-TOKEN="));
        var token = CSRF_TOKEN.matcher(loginPage.body());
        assertThat(token.find()).isTrue();

        var overlapping = overlappingRequest(sessions, plantedId);
        var login = logIn(username, token.group(1), plantedCookie + "; " + csrfCookie);
        assertThat(login.headers().firstValue("Location").orElseThrow()).doesNotContain("error");
        overlapping.run();

        var page = HttpRequest.newBuilder(uri("/settings/delete-account")).header("Accept", "text/html").GET();
        var response = send(page, plantedCookie);
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElseThrow()).endsWith("/login");
    }

    /**
     * Sessions are stored in the database, so one for every visit would let anyone fill it.
     */
    @Test
    void visitorsWhoArentLoggedInDontGetSessions() throws Exception {
        TestAccounts.makeSureAnAccountExists(actors);
        var app = send(HttpRequest.newBuilder(uri("/api/v1/apps"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("client_name=test&scopes=read&redirect_uris="
                        + URLEncoder.encode("https://client.example/callback", StandardCharsets.UTF_8))), null);
        var clientId = CLIENT_ID.matcher(app.body());
        assertThat(clientId.find()).isTrue();
        var before = storedSessions();

        var visits = List.of("/", "/login", "/signup", "/login/two-factor", "/settings",
                "/oauth/authorize?response_type=code&scope=read&client_id=" + clientId.group(1) + "&redirect_uri="
                        + URLEncoder.encode("https://client.example/callback", StandardCharsets.UTF_8));
        for (var path : visits) {
            var response = send(HttpRequest.newBuilder(uri(path)).header("Accept", "text/html").GET(), null);
            assertThat(response.statusCode()).as(path).isLessThan(400);
            assertThat(response.headers().allValues("Set-Cookie")).as(path).noneMatch(cookie -> cookie.startsWith("SESSION="));
        }

        assertThat(storedSessions()).isEqualTo(before);
    }

    private long storedSessions() {
        return jdbc.sql("SELECT count(*) FROM spring_session").query(Long.class).single();
    }

    private static <S extends Session> String newSession(SessionRepository<S> sessions) {
        var session = sessions.createSession();
        sessions.save(session);
        return session.getId();
    }

    /**
     * @return what finishing a request that's read this session already does, which is to save it
     */
    private static <S extends Session> Runnable overlappingRequest(SessionRepository<S> sessions, String id) {
        var session = sessions.findById(id);
        session.setLastAccessedTime(Instant.now());
        return () -> sessions.save(session);
    }

    @Test
    void aMalformedCookieIsJustNotASession() throws Exception {
        var encoder = Base64.getEncoder();
        for (var cookie : List.of(
                encoder.encodeToString("\\xZZ".getBytes(StandardCharsets.UTF_8)),
                encoder.encodeToString("it's; --".getBytes(StandardCharsets.UTF_8)),
                encoder.encodeToString(new byte[]{(byte) 0xff, (byte) 0xfe}),
                encoder.encodeToString(new byte[]{'a', 0, 'b'}),
                "not base64!")) {
            assertThat(send(HttpRequest.newBuilder(uri("/")).GET(), "SESSION=" + cookie).statusCode()).isEqualTo(200);
        }
    }

    /**
     * Someone who can read the database or a backup shouldn't be able to use what's there to take over a session.
     */
    private void assertOnlyTheHashIsStored(UUID actorId, String cookie, HttpRequest.Builder page) throws Exception {
        var encodedId = cookie.substring(cookie.indexOf('=') + 1);
        var id = new String(Base64.getDecoder().decode(encodedId), StandardCharsets.UTF_8);
        var stored = jdbc.sql("SELECT session_id FROM spring_session WHERE principal_name = :id")
                .param("id", actorId.toString())
                .query(String.class)
                .single();
        assertThat(stored).isNotEqualTo(id).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8))));

        var fromTheDatabase = Base64.getEncoder().encodeToString(stored.getBytes(StandardCharsets.UTF_8));
        assertThat(send(page, "SESSION=" + fromTheDatabase).statusCode()).isEqualTo(302);

        for (var secret : List.of(id, encodedId)) {
            assertThat(jdbc.sql("""
                            SELECT count(*) FROM spring_session_attributes
                            WHERE position(:secret IN attribute_bytes) > 0
                            """)
                    .param("secret", secret.getBytes(StandardCharsets.UTF_8))
                    .query(Long.class)
                    .single()).isZero();
        }
    }

    private HttpResponse<String> logIn(String username, String csrfToken, String cookie) throws Exception {
        return send(HttpRequest.newBuilder(uri("/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + username
                        + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8)
                        + "&_csrf=" + URLEncoder.encode(csrfToken, StandardCharsets.UTF_8))), cookie);
    }

    private HttpResponse<String> send(HttpRequest.Builder request, String cookie) throws Exception {
        if (cookie != null) {
            request.setHeader("Cookie", cookie);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String setCookie(HttpResponse<?> response, String prefix) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith(prefix))
                .findFirst()
                .orElseThrow();
    }

    private static String value(String setCookie) {
        return setCookie.substring(0, setCookie.indexOf(';'));
    }
}
