package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException.Failure;
import dev.biribiri.elysium.actor.InstanceProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads draft-cavage-http-signatures-12 signatures. This follows Mastodon where the draft and real servers disagree, and
 * is stricter than Mastodon in two ways:
 *
 * <ul>
 * <li>Every signature has to cover {@code (request-target)} and {@code host}, not just those on GET requests. Otherwise
 * a signature isn't tied to this server, and could be replayed here after being sent to another one.
 * <li>Times are only read from what the signature covers. If a {@code created} parameter that isn't signed could replace
 * a signed {@code Date}, then anyone could send an old request again with a new time.
 * </ul>
 */
@Component
@Order(100)
class CavageSignatureScheme implements SignatureScheme {

    private static final String SIGNATURE = "Signature";

    /**
     * RFC 9421 also uses a {@code Signature} header but in a different format, and is the only format with this header.
     */
    private static final String RFC_9421_SIGNATURE_INPUT = "Signature-Input";
    private static final String DIGEST = "Digest";

    private static final String REQUEST_TARGET = "(request-target)";
    private static final String CREATED = "(created)";
    private static final String EXPIRES = "(expires)";
    private static final String HOST = "host";
    private static final String DATE = "date";
    private static final String DIGEST_ITEM = "digest";

    private static final String RSA_SHA256 = "rsa-sha256";
    private static final String HS2019 = "hs2019";

    private static final Set<String> PSEUDO_HEADERS = Set.of(REQUEST_TARGET, CREATED, EXPIRES);
    private static final Set<String> METHODS_WITHOUT_BODIES = Set.of("GET", "HEAD");

    /**
     * Limits the digits so that the value always fits in an {@link Instant}. Only {@code (expires)} can have a fraction.
     */
    private static final Pattern CREATED_VALUE = Pattern.compile("\\d{1,12}");
    private static final Pattern EXPIRES_VALUE = Pattern.compile("(\\d{1,12})(\\.\\d+)?");

    private final InstanceProperties instance;

    CavageSignatureScheme(InstanceProperties instance) {
        this.instance = instance;
    }

    @Override
    public boolean appliesTo(HttpRequestData request) {
        return request.headers().containsHeader(SIGNATURE)
                && !request.headers().containsHeader(RFC_9421_SIGNATURE_INPUT);
    }

    @Override
    public SignatureClaim parse(HttpRequestData request) {
        var values = request.headers().get(SIGNATURE);
        if (values.size() != 1) {
            throw malformed("Request has more than one Signature header");
        }
        var parameters = CavageSignatureHeader.parse(values.getFirst());

        var keyId = required(parameters, "keyId");
        var signature = decodeBase64(required(parameters, "signature"), "signature");
        var algorithm = parameters.getOrDefault("algorithm", HS2019);
        if (!algorithm.equals(RSA_SHA256) && !algorithm.equals(HS2019)) {
            throw rejected("Unsupported signature algorithm: " + algorithm);
        }
        var covered = coveredItems(parameters);
        checkStrength(request, algorithm, covered);

        var created = created(request, parameters, covered);
        var expires = covered.contains(EXPIRES) ? expires(parameters) : null;
        var base = signatureBase(request, parameters, covered);
        if (covered.contains(DIGEST_ITEM)) {
            checkDigest(request);
        }
        // The draft says hs2019 means SHA-512 and that the algorithm comes from the key. In practice servers sign with
        // RSA and SHA-256 under either name, and Mastodon always verifies that way, so this has to as well.
        return new SignatureClaim(keyId, SignatureAlgorithm.RSA_SHA256, base, signature, created, expires);
    }

    /**
     * @return the items the signature covers, in the order they were signed
     */
    private static List<String> coveredItems(Map<String, String> parameters) {
        var headers = parameters.get("headers");
        if (headers == null) {
            return List.of(CREATED);
        }
        var names = headers.strip().toLowerCase(Locale.ROOT);
        if (names.isEmpty()) {
            throw malformed("Signature has an empty headers list");
        }
        return List.of(names.split("\\s+"));
    }

    /**
     * This also decides what {@link CavageRequestSigner} signs, so that this server accepts its own requests.
     *
     * @return whether a signature has to cover a digest. Any method but these can have a body, so even an empty one
     *         counts: otherwise, a sender could leave out the digest and have someone else add a body.
     */
    static boolean needsDigest(String method, byte[] body) {
        return !METHODS_WITHOUT_BODIES.contains(method) || body.length > 0;
    }

    private static void checkStrength(HttpRequestData request, String algorithm, List<String> covered) {
        for (var item : covered) {
            if (item.startsWith("(") && !PSEUDO_HEADERS.contains(item)) {
                throw rejected("Signature covers an unknown item: " + item);
            }
        }
        // The draft doesn't allow these with any algorithm name that starts with "rsa".
        if (algorithm.equals(RSA_SHA256) && (covered.contains(CREATED) || covered.contains(EXPIRES))) {
            throw rejected("Signature covers (created) or (expires) with rsa-sha256");
        }
        if (!covered.contains(REQUEST_TARGET) || !covered.contains(HOST)) {
            throw rejected("Signature has to cover (request-target) and host");
        }
        if (!covered.contains(DATE) && !covered.contains(CREATED)) {
            throw rejected("Signature has to cover date or (created)");
        }
        if (needsDigest(request.method(), request.body()) && !covered.contains(DIGEST_ITEM)) {
            throw rejected("Signature has to cover digest");
        }
    }

    private static Instant created(HttpRequestData request, Map<String, String> parameters, List<String> covered) {
        if (covered.contains(CREATED)) {
            var value = required(parameters, "created");
            if (!CREATED_VALUE.matcher(value).matches()) {
                throw malformed("Signature has an invalid created parameter");
            }
            return Instant.ofEpochSecond(Long.parseLong(value));
        }
        var dates = request.headers().get(HttpHeaders.DATE);
        if (dates == null) {
            throw rejected("Signature covers date, but there's no Date header");
        }
        if (dates.size() != 1) {
            throw malformed("Request has more than one Date header");
        }
        try {
            return Instant.ofEpochMilli(request.headers().getFirstDate(HttpHeaders.DATE));
        } catch (IllegalArgumentException e) {
            throw malformed("Request has an invalid Date header");
        }
    }

    private static Instant expires(Map<String, String> parameters) {
        var matcher = EXPIRES_VALUE.matcher(required(parameters, "expires"));
        if (!matcher.matches()) {
            throw malformed("Signature has an invalid expires parameter");
        }
        // Dropping any fraction moves this back by less than a second, so it never allows more than it should.
        return Instant.ofEpochSecond(Long.parseLong(matcher.group(1)));
    }

    private byte[] signatureBase(HttpRequestData request, Map<String, String> parameters, List<String> covered) {
        var lines = new ArrayList<String>(covered.size());
        for (var item : covered) {
            lines.add(item + ": " + itemValue(request, parameters, item));
        }
        // Servlet containers decode header bytes as ISO-8859-1, so this gets back the bytes that were sent.
        return String.join("\n", lines).getBytes(StandardCharsets.ISO_8859_1);
    }

    private String itemValue(HttpRequestData request, Map<String, String> parameters, String item) {
        return switch (item) {
            case REQUEST_TARGET -> request.method().toLowerCase(Locale.ROOT) + " " + request.rawTarget();
            case CREATED -> required(parameters, "created");
            case EXPIRES -> required(parameters, "expires");
            // Always this server's own name, rather than what the request says. A reverse proxy can change the header,
            // and nginx's usual $host drops the port. This also means a signature made for another server can't pass.
            case HOST -> instance.domain();
            default -> {
                var values = request.headers().get(item);
                if (values == null) {
                    throw rejected("Signature covers a header that's missing: " + item);
                }
                yield String.join(", ", values.stream().map(String::strip).toList());
            }
        };
    }

    /**
     * Mastodon only accepts SHA-256, and so every server has to send it. Any other digests in the header are ignored.
     */
    private static void checkDigest(HttpRequestData request) {
        var digests = request.headers().get(DIGEST);
        if (digests == null) {
            throw rejected("Signature covers digest, but there's no Digest header");
        }
        for (var entry : String.join(",", digests).split(",")) {
            // Only the first one splits, because base64 padding also has equals signs.
            var parts = entry.strip().split("=", 2);
            if (parts.length == 2 && parts[0].equalsIgnoreCase("sha-256")) {
                if (!MessageDigest.isEqual(decodeBase64(parts[1], "Digest"), Digests.sha256(request.body()))) {
                    throw rejected("Digest doesn't match the request's body");
                }
                return;
            }
        }
        throw rejected("Digest header doesn't have a SHA-256 digest");
    }

    private static String required(Map<String, String> parameters, String name) {
        var value = parameters.get(name);
        if (value == null) {
            throw rejected("Signature is missing its " + name + " parameter");
        }
        return value;
    }

    private static byte[] decodeBase64(String value, String name) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw malformed(name + " isn't valid base64");
        }
    }

    private static SignatureVerificationException malformed(String message) {
        return new SignatureVerificationException(Failure.MALFORMED, message);
    }

    private static SignatureVerificationException rejected(String message) {
        return new SignatureVerificationException(Failure.REJECTED, message);
    }
}
