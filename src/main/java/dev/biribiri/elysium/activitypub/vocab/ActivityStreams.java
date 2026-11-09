package dev.biribiri.elysium.activitypub.vocab;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * The URIs and media types that ActivityPub documents and requests are identified by.
 */
public final class ActivityStreams {

    /**
     * The ActivityStreams {@code @context}, which is also the profile of its {@code application/ld+json} media type.
     */
    public static final String CONTEXT = "https://www.w3.org/ns/activitystreams";

    /**
     * The collection that addresses an object to everyone.
     */
    public static final String PUBLIC = CONTEXT + "#Public";

    public static final String ACTIVITY_JSON = "application/activity+json";

    /**
     * The type that ActivityPub defines. As a {@code produces} value next to {@link #ANY_LD_JSON}, this is what a request for
     * {@code application/ld+json} with no profile gets, since other servers refuse that type without the profile.
     */
    public static final String LD_JSON = "application/ld+json; profile=\"" + CONTEXT + "\"";

    /**
     * As a {@code produces} or {@code consumes} value, this matches any profile, since servers send variations such as the
     * {@code http} form of the ActivityStreams profile, or several profiles at once. A response repeats a profile that the
     * request asked for.
     */
    public static final String ANY_LD_JSON = "application/ld+json";

    /**
     * The {@code @context} of an actor document, which includes the terms for its {@code publicKey}.
     */
    static final List<String> ACTOR_CONTEXT = List.of(CONTEXT, "https://w3id.org/security/v1");

    /**
     * The {@code @context} of a local account's actor document, which adds the terms Mastodon uses for profile fields.
     */
    static final List<Object> PERSON_CONTEXT = List.of(
            CONTEXT,
            "https://w3id.org/security/v1",
            Map.of("schema", "http://schema.org#", "PropertyValue", "schema:PropertyValue", "value", "schema:value",
                    Hashtag.CONTEXT_TERM, Hashtag.CONTEXT_IRI));

    /**
     * The {@code @context} of an actor document or activity saying that the actor is suspended, which adds Mastodon's term for
     * that.
     */
    static final List<Object> SUSPENDED_ACTOR_CONTEXT = List.of(
            CONTEXT,
            "https://w3id.org/security/v1",
            Map.of("toot", "http://joinmastodon.org/ns#", "suspended", "toot:suspended"));

    private ActivityStreams() {
    }

    /**
     * Whole seconds, since some servers can't read fractions.
     */
    static String time(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }
}
