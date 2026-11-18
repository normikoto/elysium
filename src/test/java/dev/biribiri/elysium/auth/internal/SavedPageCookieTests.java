package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.InstanceProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class SavedPageCookieTests {

    private static final BrowserCookies HTTPS = new BrowserCookies(new InstanceProperties(URI.create("https://elysium.example")));

    @Test
    void overHttpsOnlyThisHostCanSetIt() {
        var request = new MockHttpServletRequest("GET", "/oauth/authorize");
        request.setQueryString("client_id=abc&state=x");
        var response = new MockHttpServletResponse();

        new SavedPageCookie(HTTPS).saveRequest(request, response);

        assertThat(response.getHeader("Set-Cookie"))
                .startsWith("__Host-SAVED-PAGE=")
                .contains("; Path=/", "; Secure", "; HttpOnly", "; SameSite=Lax")
                .doesNotContain("Domain=");
    }

    @Test
    void returnsToTheSavedPage() {
        var saved = new MockHttpServletRequest("GET", "/oauth/authorize");
        saved.setQueryString("client_id=abc&state=x");
        var response = new MockHttpServletResponse();
        new SavedPageCookie(HTTPS).saveRequest(saved, response);
        var value = response.getHeader("Set-Cookie").split("[=;]", 3)[1];

        var login = new MockHttpServletRequest("POST", "/login");
        login.setCookies(new Cookie("__Host-SAVED-PAGE", value));

        assertThat(new SavedPageCookie(HTTPS).getRequest(login, null).getRedirectUrl())
                .isEqualTo("http://localhost/oauth/authorize?client_id=abc&state=x");
    }

    @Test
    void aPlantedCookieCantLeaveThisServer() {
        for (var page : new String[]{"https://evil.example/", "//evil.example/", "/\\evil.example/", "evil"}) {
            var request = new MockHttpServletRequest("POST", "/login");
            request.setCookies(new Cookie("__Host-SAVED-PAGE",
                    Base64.getUrlEncoder().encodeToString(page.getBytes(StandardCharsets.UTF_8))));
            assertThat(new SavedPageCookie(HTTPS).getRequest(request, null)).as(page).isNull();
        }
        var request = new MockHttpServletRequest("POST", "/login");
        request.setCookies(new Cookie("__Host-SAVED-PAGE", "not base64!"));
        assertThat(new SavedPageCookie(HTTPS).getRequest(request, null)).isNull();
    }
}
