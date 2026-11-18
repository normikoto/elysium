package dev.biribiri.elysium.auth.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.security.web.util.UrlUtils;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.WebUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;

/**
 * Keeps the page to return to after logging in, such as a pending authorization, in a cookie rather than a session, so that being
 * sent to the login page doesn't store anything. It's Spring's {@code CookieRequestCache}, which can't be given a cookie name, with
 * the names and attributes of {@link BrowserCookies}. Only the path and query are kept, and only the same kind of value is read
 * back, so a planted cookie can't send anyone to another site.
 */
class SavedPageCookie implements RequestCache {

    private final BrowserCookies cookies;
    private final String name;
    private RequestMatcher requestMatcher = AnyRequestMatcher.INSTANCE;

    SavedPageCookie(BrowserCookies cookies) {
        this.cookies = cookies;
        this.name = cookies.name("SAVED-PAGE");
    }

    void setRequestMatcher(RequestMatcher requestMatcher) {
        this.requestMatcher = requestMatcher;
    }

    @Override
    public void saveRequest(HttpServletRequest request, HttpServletResponse response) {
        if (!requestMatcher.matches(request)) {
            return;
        }
        var query = request.getQueryString();
        var page = request.getRequestURI() + (query == null ? "" : "?" + query);
        write(response, Base64.getUrlEncoder().encodeToString(page.getBytes(StandardCharsets.UTF_8)), -1);
    }

    @Override
    public @Nullable SavedRequest getRequest(HttpServletRequest request, @Nullable HttpServletResponse response) {
        var cookie = WebUtils.getCookie(request, name);
        if (cookie == null) {
            return null;
        }
        String page;
        try {
            page = new String(Base64.getUrlDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!page.startsWith("/") || page.startsWith("//") || page.contains("\\")) {
            return null;
        }
        // Split rather than parsed, so that the query comes back exactly as it was sent. getMatchingRequest compares it with
        // the request that returns to the page, and a page that never matched would be returned to after every login.
        var question = page.indexOf('?');
        return new DefaultSavedRequest.Builder()
                .setScheme(request.getScheme())
                .setServerName(request.getServerName())
                .setServerPort(request.getServerPort())
                .setRequestURI(question < 0 ? page : page.substring(0, question))
                .setQueryString(question < 0 ? null : page.substring(question + 1))
                .setMethod("GET")
                .setLocales(Collections.list(request.getLocales()))
                .build();
    }

    @Override
    public @Nullable HttpServletRequest getMatchingRequest(HttpServletRequest request, HttpServletResponse response) {
        var saved = getRequest(request, response);
        if (saved == null || !saved.getRedirectUrl().equals(UrlUtils.buildFullRequestUrl(request))) {
            return null;
        }
        removeRequest(request, response);
        // Spring wraps it to bring back a saved form's parameters, and only pages are saved here.
        return request;
    }

    @Override
    public void removeRequest(HttpServletRequest request, HttpServletResponse response) {
        if (WebUtils.getCookie(request, name) != null) {
            write(response, "", 0);
        }
    }

    private void write(HttpServletResponse response, String value, int maxAge) {
        var cookie = ResponseCookie.from(name, value).httpOnly(true).maxAge(maxAge);
        cookies.customize(cookie);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
    }
}
