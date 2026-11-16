package dev.biribiri.elysium.activitypub.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler for a document that needs a valid signature when the admin requires signed fetches. It's on the handler
 * rather than the URL, because the same URLs serve web pages, which must keep working for people.
 *
 * <p>The instance actor never has this. A server checking this server's signature reads that key, and may need its own
 * request signed in turn, so the two could end up waiting on each other.
 *
 * @see SignedFetchInterceptor
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@interface SignedFetch {

    /**
     * @return true if an unsigned request is still answered, for the handler to serve less. So is one whose signature
     * can't be accepted, unless it's from a blocked server or a suspended actor, which are refused either way.
     */
    boolean allowUnsigned() default false;
}
