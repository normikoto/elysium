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

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Logs in through a real server, because the session cookie's attributes differ under MockMvc. There, Spring Boot treats the
 * application as a WAR, and copies them from a mock servlet context that doesn't turn on {@code HttpOnly}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.servlet.session.timeout=2h")
class LoginSessionCookieIntegrationTests {

    private static final String PASSWORD = "a long enough password";
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

    @Test
    void theCookieCantBeReadByScriptsAndLoggingInAndResettingWork() throws Exception {
        TestAccounts.makeSureAnAccountExists(actors);
        var username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 25);
        accounts.createAccount(username, PASSWORD).orElseThrow();

        var loginPage = send(HttpRequest.newBuilder(uri("/login")).GET(), null);
        var firstCookie = setCookie(loginPage);
        assertThat(firstCookie).startsWith("SESSION=").contains("; HttpOnly").contains("; SameSite=Lax");
        var token = CSRF_TOKEN.matcher(loginPage.body());
        assertThat(token.find()).isTrue();

        var login = send(HttpRequest.newBuilder(uri("/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + username
                        + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8)
                        + "&_csrf=" + URLEncoder.encode(token.group(1), StandardCharsets.UTF_8))), value(firstCookie));
        assertThat(login.statusCode()).isEqualTo(302);
        assertThat(login.headers().firstValue("Location").orElseThrow()).doesNotContain("error");
        var loggedIn = setCookie(login);
        // The ID changes when logging in, so that an ID someone planted before then is no use.
        assertThat(value(loggedIn)).isNotEqualTo(value(firstCookie));

        var actorId = actors.findLocalByUsername(username).orElseThrow().id();
        assertThat(jdbc.sql("SELECT max_inactive_interval FROM spring_session WHERE principal_name = :id")
                .param("id", actorId.toString())
                .query(Integer.class)
                .single()).isEqualTo(7200);

        var page = HttpRequest.newBuilder(uri("/settings/delete-account")).header("Accept", "text/html").GET();
        assertThat(send(page, value(loggedIn)).statusCode()).isEqualTo(200);

        accounts.resetPassword(actorId, "another long enough password");

        var afterReset = send(page, value(loggedIn));
        assertThat(afterReset.statusCode()).isEqualTo(302);
        assertThat(afterReset.headers().firstValue("Location").orElseThrow()).endsWith("/login");
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

    private static String setCookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith("SESSION="))
                .findFirst()
                .orElseThrow();
    }

    private static String value(String setCookie) {
        return setCookie.substring(0, setCookie.indexOf(';'));
    }
}
