package dev.biribiri.elysium.push.internal;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

/**
 * RFC 8292's tokens, which tell a push service which server sent a message. The service checks them against the public
 * key that the client subscribed with, so no one else can send to a subscription.
 */
public final class VapidTokens {

    /**
     * Push services refuse tokens that last more than a day.
     */
    static final Duration LIFETIME = Duration.ofHours(12);

    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    private static final String HEADER = BASE64URL.encodeToString(
            "{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));

    private VapidTokens() {
    }

    /**
     * @param endpoint whose origin the token is for
     * @param subject  how the push service's operator can reach this server's, which is its URL
     */
    public static String create(URI endpoint, URI subject, PrivateKey key, Instant now) {
        var claims = "{\"aud\":\"" + origin(endpoint) + "\",\"exp\":" + now.plus(LIFETIME).getEpochSecond()
                + ",\"sub\":\"" + subject + "\"}";
        var signed = HEADER + "." + BASE64URL.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        try {
            // ES256 needs the signature as r and s side by side, rather than the DER that plain SHA256withECDSA gives.
            var signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(key);
            signature.update(signed.getBytes(StandardCharsets.US_ASCII));
            return signed + "." + BASE64URL.encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String origin(URI endpoint) {
        var port = endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort();
        // Push services compare it with the endpoint's origin, which is lowercase however the client wrote the URL.
        return endpoint.getScheme().toLowerCase(Locale.ROOT) + "://" + endpoint.getHost().toLowerCase(Locale.ROOT) + port;
    }
}
