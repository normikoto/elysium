package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.actor.signing.SigningKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Locale;

/**
 * Signs requests with draft-cavage-http-signatures-12, in the form Mastodon sends. The draft deprecates naming
 * {@code rsa-sha256} as the algorithm in favor of {@code hs2019}, but it's the most widely understood value.
 */
@Component
public class CavageRequestSigner implements RequestSigner {

    private final Clock clock;

    @Autowired
    CavageRequestSigner() {
        this(Clock.systemUTC());
    }

    CavageRequestSigner(Clock clock) {
        this.clock = clock;
    }

    @Override
    public HttpHeaders sign(String method, URI target, byte[] body, SigningKey key) {
        var added = new HttpHeaders();
        // Spring writes a proper HTTP date. DateTimeFormatter.RFC_1123_DATE_TIME doesn't pad single-digit days, which
        // other servers can reject.
        added.setDate(clock.millis());

        var names = new ArrayList<String>();
        var lines = new ArrayList<String>();
        names.add("(request-target)");
        lines.add("(request-target): " + method.toLowerCase(Locale.ROOT) + " " + requestTarget(target));
        names.add("host");
        lines.add("host: " + host(target));
        names.add("date");
        lines.add("date: " + added.getFirst(HttpHeaders.DATE));
        if (body.length > 0) {
            added.set("Digest", "SHA-256=" + Base64.getEncoder().encodeToString(sha256(body)));
            names.add("digest");
            lines.add("digest: " + added.getFirst("Digest"));
        }

        var base = String.join("\n", lines).getBytes(StandardCharsets.ISO_8859_1);
        var signature = SignatureAlgorithm.RSA_SHA256.sign(key.privateKey(), base);
        added.set("Signature", "keyId=\"%s\",algorithm=\"rsa-sha256\",headers=\"%s\",signature=\"%s\"".formatted(
                key.keyId(), String.join(" ", names), Base64.getEncoder().encodeToString(signature)));
        return added;
    }

    private static String requestTarget(URI target) {
        var ascii = URI.create(target.toASCIIString());
        var path = ascii.getRawPath() == null || ascii.getRawPath().isEmpty() ? "/" : ascii.getRawPath();
        return ascii.getRawQuery() == null ? path : path + "?" + ascii.getRawQuery();
    }

    /**
     * Leaves out a default port, as HTTP clients do when writing the {@code Host} header.
     */
    private static String host(URI target) {
        var scheme = target.getScheme().toLowerCase(Locale.ROOT);
        var host = target.getHost().toLowerCase(Locale.ROOT);
        var port = target.getPort();
        var defaultPort = scheme.equals("https") ? 443 : 80;
        return port == -1 || port == defaultPort ? host : host + ":" + port;
    }

    private static byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
