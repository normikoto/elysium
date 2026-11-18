package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.InstanceProperties;
import org.springframework.http.ResponseCookie;

/**
 * Names and attributes for the cookies that let a visitor who isn't logged in use the login and sign-up forms without a session.
 * Sessions are stored in the database, so one for every visit would let anyone fill it.
 *
 * <p>Over HTTPS, the names have the {@code __Host-} prefix, which browsers only accept from this host itself, over HTTPS, for the
 * whole site. Without it, a page on a sibling subdomain could plant a CSRF token it knows, or a page to return to. That comes from
 * the configured URL and not each request, because behind a proxy that ends TLS, a request can look like plain HTTP when the browser
 * used HTTPS. The prefix can't be used for plain HTTP, which is only for development.
 */
record BrowserCookies(boolean secure) {

    BrowserCookies(InstanceProperties instance) {
        this(instance.baseUrl().getScheme().equals("https"));
    }

    String name(String name) {
        return secure ? "__Host-" + name : name;
    }

    /**
     * Lax, as for the session cookie, so that following a link from another site, such as a client's link to authorize it, still
     * sends it.
     */
    void customize(ResponseCookie.ResponseCookieBuilder cookie) {
        cookie.secure(secure).path("/").domain(null).sameSite("Lax");
    }
}
