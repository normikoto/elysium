package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gets tokens the way a real application does. Logging in is slow because of Argon2, so tests should reuse them.
 */
public final class TestOAuth {

    private static final String REDIRECT_URI = "https://app.example/callback";

    private TestOAuth() {
    }

    public static Map<String, String> registerApp(MockMvc mockMvc, String scopes) throws Exception {
        var body = mockMvc.perform(post("/api/v1/apps")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("client_name", "Test")
                        .param("redirect_uris", REDIRECT_URI)
                        .param("scopes", scopes))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Map.of(
                "client_id", JsonPath.read(body, "$.client_id"),
                "client_secret", JsonPath.read(body, "$.client_secret"));
    }

    public static MockHttpSession logIn(MockMvc mockMvc, String username, String password) throws Exception {
        var session = new MockHttpSession();
        mockMvc.perform(post("/login").with(csrf()).session(session)
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().is3xxRedirection());
        return session;
    }

    /**
     * Registers an application, logs in, approves it, and exchanges the code for a token.
     */
    public static String userToken(MockMvc mockMvc, String username, String password, String scope) throws Exception {
        var app = registerApp(mockMvc, scope);
        var session = logIn(mockMvc, username, password);
        var consentUrl = mockMvc.perform(get("/oauth/authorize").session(session)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", app.get("client_id"))
                        .queryParam("redirect_uri", REDIRECT_URI)
                        .queryParam("scope", scope))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        var approve = post("/oauth/authorize").session(session)
                .param("client_id", app.get("client_id"))
                .param("state", queryParameter(consentUrl, "state"));
        for (var requested : queryParameter(consentUrl, "scope").split(" ")) {
            approve.param("scope", requested);
        }
        var callback = mockMvc.perform(approve)
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        var body = mockMvc.perform(post("/oauth/token")
                        .param("grant_type", "authorization_code")
                        .param("client_id", app.get("client_id"))
                        .param("client_secret", app.get("client_secret"))
                        .param("code", queryParameter(callback, "code"))
                        .param("redirect_uri", REDIRECT_URI))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.access_token");
    }

    private static String queryParameter(String url, String name) {
        var value = UriComponentsBuilder.fromUri(URI.create(url)).build().getQueryParams().getFirst(name);
        return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
