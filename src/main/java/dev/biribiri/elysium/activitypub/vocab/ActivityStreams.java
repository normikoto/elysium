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
     * The W3C Security Vocabulary {@code @context} that defines some security-related terms.
     */
    public static final String W3ID_SECURITY_V1 = "https://w3id.org/security/v1";

    private static final String SECURITY = "https://w3id.org/security#";

    /**
     * The terms of FEP-521a's {@code assertionMethod}, which mean what the Controlled Identifiers {@code @context}
     * ({@code https://www.w3.org/ns/cid/v1}) has them mean. They're written out rather than naming that context, because it's
     * protected, and JSON-LD refuses a document that defines one of its terms again differently, as the terms for Mastodon's
     * profiles could.
     */
    private static final Map<String, Object> MULTIKEY_TERMS = Map.of(
            "assertionMethod", Map.of("@id", SECURITY + "assertionMethod", "@type", "@id", "@container", "@set"),
            "Multikey", SECURITY + "Multikey",
            "controller", Map.of("@id", SECURITY + "controller", "@type", "@id"),
            "publicKeyMultibase", SECURITY + "publicKeyMultibase");

    /**
     * The {@code @context} of a document whose items are {@link Hashtag}s, which aren't in the ActivityStreams vocabulary.
     */
    static final List<Object> HASHTAG_CONTEXT = List.of(CONTEXT, Map.of(Hashtag.CONTEXT_TERM, Hashtag.CONTEXT_IRI));

    /**
     * The {@code @context} of an actor document, which includes the terms for its {@code publicKey} and
     * {@code assertionMethod}.
     */
    static final List<Object> ACTOR_CONTEXT = List.of(CONTEXT, W3ID_SECURITY_V1, MULTIKEY_TERMS);

    /**
     * The {@code @context} of a local account's actor document, which adds the terms Mastodon uses for profile fields.
     */
    static final List<Object> PERSON_CONTEXT = List.of(
            CONTEXT,
            W3ID_SECURITY_V1,
            Map.ofEntries(
                    Map.entry("schema", "http://schema.org#"),
                    Map.entry("PropertyValue", "schema:PropertyValue"),
                    Map.entry("value", "schema:value"),
                    Map.entry(Hashtag.CONTEXT_TERM, Hashtag.CONTEXT_IRI),
                    Map.entry(Emoji.CONTEXT_TERM, Emoji.CONTEXT_IRI),
                    Map.entry("manuallyApprovesFollowers", "as:manuallyApprovesFollowers"),
                    // Mastodon's terms, rather than FEP-7628's, since they're the ones other servers read.
                    Map.entry("alsoKnownAs", Map.of("@id", "as:alsoKnownAs", "@type", "@id")),
                    Map.entry("movedTo", Map.of("@id", "as:movedTo", "@type", "@id")),
                    Map.entry("toot", "http://joinmastodon.org/ns#"),
                    Map.entry("featured", Map.of("@id", "toot:featured", "@type", "@id")),
                    Map.entry("featuredTags", Map.of("@id", "toot:featuredTags", "@type", "@id")),
                    Map.entry("indexable", "toot:indexable"),
                    Map.entry("discoverable", "toot:discoverable"),
                    Map.entry("misskey", "https://misskey-hub.net/ns#"),
                    Map.entry("isCat", "misskey:isCat")),
            MULTIKEY_TERMS);

    /**
     * The {@code @context} of an actor document or activity saying that the actor is suspended, which adds Mastodon's term for
     * that.
     */
    static final List<Object> SUSPENDED_ACTOR_CONTEXT = List.of(
            CONTEXT,
            W3ID_SECURITY_V1,
            Map.of("toot", "http://joinmastodon.org/ns#", "suspended", "toot:suspended"),
            MULTIKEY_TERMS);

    private ActivityStreams() {
    }

    /**
     * Whole seconds, since some servers can't read fractions.
     */
    static String time(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }
}
