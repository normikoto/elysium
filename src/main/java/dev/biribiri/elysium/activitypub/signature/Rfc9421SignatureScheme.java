package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException.Failure;
import dev.biribiri.elysium.activitypub.signature.StructuredFields.InnerList;
import dev.biribiri.elysium.activitypub.signature.StructuredFields.Item;
import dev.biribiri.elysium.actor.InstanceProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads RFC 9421 HTTP Message Signatures. Ordered ahead of {@link CavageSignatureScheme}, although the two never apply to
 * the same request, so that the RFC wins if that ever changes.
 *
 * <p>What a signature has to cover follows the cavage scheme's rules, so that neither format is the weaker way in:
 *
 * <ul>
 * <li>The method, and this server and the target, through {@code @target-uri}, or {@code @authority} with
 * {@code @request-target}, or with {@code @path} and {@code @query}. Otherwise a signature could be replayed here after
 * being sent to another server, or to another path.
 * <li>{@code content-digest} wherever cavage needs {@code digest}.
 * <li>A {@code created} time. Parameters are always part of what's signed, so unlike cavage, this can't be swapped.
 * </ul>
 *
 * <p>A request can carry several signatures, which proxies and other software add for their own reasons. The first one
 * that's good enough is used, so another one can't stop an otherwise acceptable request.
 */
@Component
@Order(50)
class Rfc9421SignatureScheme implements SignatureScheme {

    static final String SIGNATURE_INPUT = "Signature-Input";
    static final String SIGNATURE = "Signature";
    static final String CONTENT_DIGEST = "Content-Digest";
    static final String ALGORITHM = "rsa-v1_5-sha256";

    static final String METHOD = "@method";
    static final String TARGET_URI = "@target-uri";
    static final String AUTHORITY = "@authority";
    static final String SCHEME = "@scheme";
    static final String REQUEST_TARGET = "@request-target";
    static final String PATH = "@path";
    static final String QUERY = "@query";
    static final String CONTENT_DIGEST_COMPONENT = "content-digest";
    private static final String SIGNATURE_PARAMS = "@signature-params";

    private static final Set<String> DERIVED_COMPONENTS =
            Set.of(METHOD, TARGET_URI, AUTHORITY, SCHEME, REQUEST_TARGET, PATH, QUERY);

    /**
     * The algorithms RFC 9530 registers as fit for use. Any others are ignored, as the RFC allows.
     */
    private static final Map<String, String> DIGEST_ALGORITHMS = Map.of("sha-256", "SHA-256", "sha-512", "SHA-512");

    private final InstanceProperties instance;

    Rfc9421SignatureScheme(InstanceProperties instance) {
        this.instance = instance;
    }

    @Override
    public boolean appliesTo(HttpRequestData request) {
        return request.headers().containsHeader(SIGNATURE_INPUT) && request.headers().containsHeader(SIGNATURE);
    }

    @Override
    public SignatureClaim parse(HttpRequestData request) {
        var inputs = StructuredFields.parseDictionary(field(request, SIGNATURE_INPUT));
        var signatures = StructuredFields.parseDictionary(field(request, SIGNATURE));
        SignatureVerificationException firstFailure = null;
        for (var input : inputs.entrySet()) {
            try {
                return claim(request, input.getKey(), input.getValue(), signatures.get(input.getKey()));
            } catch (SignatureVerificationException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        throw firstFailure != null ? firstFailure : rejected("Signature-Input has no signatures");
    }

    private SignatureClaim claim(HttpRequestData request, String label, Object input, Object signatureMember) {
        if (!(input instanceof InnerList components)) {
            throw malformed("Signature-Input " + label + " isn't a list of components");
        }
        if (signatureMember == null) {
            throw rejected("Signature has no " + label + " for its Signature-Input");
        }
        if (!(signatureMember instanceof Item(byte[] signature, var _))) {
            throw malformed("Signature " + label + " isn't a byte sequence");
        }

        var parameters = components.parameters();
        var keyId = stringParameter(parameters, "keyid");
        if (keyId == null) {
            throw rejected("Signature " + label + " is missing its keyid");
        }
        var algorithm = stringParameter(parameters, "alg");
        if (algorithm != null && !algorithm.equals(ALGORITHM)) {
            throw rejected("Unsupported signature algorithm: " + algorithm);
        }
        var created = timeParameter(parameters, "created");
        if (created == null) {
            throw rejected("Signature " + label + " is missing its created time");
        }
        var expires = timeParameter(parameters, "expires");

        var names = componentNames(components);
        checkStrength(request, names);
        var base = signatureBase(request, components);
        if (names.contains(CONTENT_DIGEST_COMPONENT)) {
            checkContentDigest(request);
        }
        // Fediverse keys are RSA, and Mastodon assumes this algorithm when a signature doesn't name one.
        return new SignatureClaim(keyId, SignatureAlgorithm.RSA_SHA256, base, signature, created, expires);
    }

    /**
     * Component parameters such as {@code sf} and {@code req} change how a value is derived. No server is known to send
     * them, so they're refused rather than supported half-way.
     */
    private static List<String> componentNames(InnerList components) {
        var names = new ArrayList<String>(components.items().size());
        var seen = new HashSet<String>();
        for (var component : components.items()) {
            if (!(component.value() instanceof String name)) {
                throw malformed("Signature-Input has a component that isn't a string");
            }
            if (!component.parameters().isEmpty()) {
                throw rejected("Signature covers a component with parameters: " + name);
            }
            var supported = name.startsWith("@")
                    ? DERIVED_COMPONENTS.contains(name)
                    : name.equals(name.toLowerCase(Locale.ROOT));
            if (!supported) {
                throw rejected("Signature covers an unsupported component: " + name);
            }
            if (!seen.add(name)) {
                throw malformed("Signature covers " + name + " more than once");
            }
            names.add(name);
        }
        return names;
    }

    private static void checkStrength(HttpRequestData request, List<String> names) {
        if (!names.contains(METHOD)) {
            throw rejected("Signature has to cover @method");
        }
        var target = names.contains(TARGET_URI)
                || (names.contains(AUTHORITY)
                && (names.contains(REQUEST_TARGET) || (names.contains(PATH) && names.contains(QUERY))));
        if (!target) {
            throw rejected("Signature has to cover @target-uri, or @authority and the path and query");
        }
        if (SignedRequests.needsDigest(request.method(), request.body()) && !names.contains(CONTENT_DIGEST_COMPONENT)) {
            throw rejected("Signature has to cover content-digest");
        }
    }

    /**
     * The parameters line is serialized again rather than copied, as RFC 9421 says, so it's always in canonical form.
     * This doesn't check whether the signature covers enough, so that tests can check it with the RFC's examples, which
     * don't.
     *
     * @throws SignatureVerificationException if a component isn't supported, or a header it covers is missing
     */
    byte[] signatureBase(HttpRequestData request, InnerList components) {
        var base = new StringBuilder();
        for (var name : componentNames(components)) {
            base.append(StructuredFields.serialize(new Item(name, new LinkedHashMap<>())))
                    .append(": ")
                    .append(componentValue(request, name))
                    .append('\n');
        }
        base.append('"').append(SIGNATURE_PARAMS).append("\": ").append(StructuredFields.serialize(components));
        // Servlet containers decode header bytes as ISO-8859-1, so this gets back the bytes that were sent.
        return base.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private String componentValue(HttpRequestData request, String name) {
        var target = request.rawTarget();
        var query = target.indexOf('?');
        return switch (name) {
            case METHOD -> request.method();
            // Always this server's own name and scheme, rather than what the request says. A reverse proxy can change
            // these, and this means a signature made for another server can't pass.
            case AUTHORITY -> instance.domain();
            case SCHEME -> instance.baseUrl().getScheme();
            case TARGET_URI -> instance.origin() + target;
            case REQUEST_TARGET -> target;
            case PATH -> query == -1 ? target : target.substring(0, query);
            case QUERY -> query == -1 ? "?" : target.substring(query);
            default -> {
                var values = request.headers().get(name);
                if (values == null) {
                    throw rejected("Signature covers a header that's missing: " + name);
                }
                yield String.join(", ", values.stream().map(String::strip).toList());
            }
        };
    }

    /**
     * Every digest this server knows how to check has to match, so a sender can't hide a wrong one behind a right one.
     */
    private static void checkContentDigest(HttpRequestData request) {
        var digests = StructuredFields.parseDictionary(field(request, CONTENT_DIGEST));
        var checked = false;
        for (var digest : digests.entrySet()) {
            var algorithm = DIGEST_ALGORITHMS.get(digest.getKey());
            if (algorithm == null) {
                continue;
            }
            if (!(digest.getValue() instanceof Item(byte[] expected, var _))) {
                throw malformed("Content-Digest " + digest.getKey() + " isn't a byte sequence");
            }
            if (!MessageDigest.isEqual(expected, digest(algorithm, request.body()))) {
                throw rejected("Content-Digest doesn't match the request's body");
            }
            checked = true;
        }
        if (!checked) {
            throw rejected("Content-Digest doesn't have a SHA-256 or SHA-512 digest");
        }
    }

    /**
     * @return the field's lines joined as RFC 9651 says, or empty if it's missing, which parses as an empty dictionary
     */
    private static String field(HttpRequestData request, String name) {
        var values = request.headers().get(name);
        return values == null ? "" : String.join(", ", values);
    }

    private static String stringParameter(Map<String, Object> parameters, String name) {
        var value = parameters.get(name);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw malformed("Signature-Input's " + name + " isn't a string");
    }

    private static Instant timeParameter(Map<String, Object> parameters, String name) {
        var value = parameters.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Long seconds)) {
            throw malformed("Signature-Input's " + name + " isn't an integer");
        }
        // Fifteen digits at most, so this always fits.
        return Instant.ofEpochSecond(seconds);
    }

    private static byte[] digest(String algorithm, byte[] body) {
        try {
            return MessageDigest.getInstance(algorithm).digest(body);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is not available", e);
        }
    }

    private static SignatureVerificationException malformed(String message) {
        return new SignatureVerificationException(Failure.MALFORMED, message);
    }

    private static SignatureVerificationException rejected(String message) {
        return new SignatureVerificationException(Failure.REJECTED, message);
    }
}
