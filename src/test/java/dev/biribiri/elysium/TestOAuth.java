package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
        return registerApp(mockMvc, "Test", null, scopes);
    }

    /**
     * @param website the application's homepage, or null
     */
    public static Map<String, String> registerApp(MockMvc mockMvc, String name, String website, String scopes)
            throws Exception {
        var request = post("/api/v1/apps")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("client_name", name)
                .param("redirect_uris", REDIRECT_URI)
                .param("scopes", scopes);
        if (website != null) {
            request.param("website", website);
        }
        var body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Map.of(
                "id", JsonPath.read(body, "$.id"),
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
        return userToken(mockMvc, registerApp(mockMvc, scope), logIn(mockMvc, username, password), scope);
    }

    /**
     * Approves an application that's already registered, for whoever is logged in to the session.
     */
    public static String userToken(MockMvc mockMvc, Map<String, String> app, MockHttpSession session, String scope)
            throws Exception {
        return exchangeCode(mockMvc, app, approve(mockMvc, app, session, authorize(mockMvc, app, session, scope)));
    }

    /**
     * @return where the authorization endpoint redirects to: the consent page, or a code if the scopes are already approved
     */
    public static String authorize(MockMvc mockMvc, Map<String, String> app, MockHttpSession session, String scope)
            throws Exception {
        return mockMvc.perform(get("/oauth/authorize").session(session)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", app.get("client_id"))
                        .queryParam("redirect_uri", REDIRECT_URI)
                        .queryParam("scope", scope))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
    }

    /**
     * @return the callback URL, which has the code in it
     */
    public static String approve(MockMvc mockMvc, Map<String, String> app, MockHttpSession session, String consentUrl)
            throws Exception {
        var approve = post("/oauth/authorize").session(session)
                .param("client_id", app.get("client_id"))
                .param("state", queryParameter(consentUrl, "state"));
        for (var requested : queryParameter(consentUrl, "scope").split(" ")) {
            approve.param("scope", requested);
        }
        return mockMvc.perform(approve)
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
    }

    /**
     * Submits the consent page with nothing ticked, as its Deny button does.
     */
    public static void deny(MockMvc mockMvc, Map<String, String> app, MockHttpSession session, String consentUrl)
            throws Exception {
        mockMvc.perform(post("/oauth/authorize").session(session)
                        .param("client_id", app.get("client_id"))
                        .param("state", queryParameter(consentUrl, "state")))
                .andExpect(status().is3xxRedirection());
    }

    public static MockHttpServletRequestBuilder tokenRequest(Map<String, String> app, String callback) {
        return post("/oauth/token")
                .param("grant_type", "authorization_code")
                .param("client_id", app.get("client_id"))
                .param("client_secret", app.get("client_secret"))
                .param("code", queryParameter(callback, "code"))
                .param("redirect_uri", REDIRECT_URI);
    }

    private static String exchangeCode(MockMvc mockMvc, Map<String, String> app, String callback) throws Exception {
        var body = mockMvc.perform(tokenRequest(app, callback))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.access_token");
    }

    private static String queryParameter(String url, String name) {
        var value = UriComponentsBuilder.fromUri(URI.create(url)).build().getQueryParams().getFirst(name);
        return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
