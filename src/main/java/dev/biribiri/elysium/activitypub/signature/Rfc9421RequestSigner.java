package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.signature.StructuredFields.InnerList;
import dev.biribiri.elysium.activitypub.signature.StructuredFields.Item;
import dev.biribiri.elysium.actor.signing.SigningKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.SequencedMap;

/**
 * Signs requests with RFC 9421 HTTP Message Signatures, in the form Mastodon sends, plus an {@code alg} so that servers
 * that need one don't have to guess.
 *
 * <p>Ordered after {@link CavageRequestSigner}, so this is only used once a server refuses a cavage signature. The
 * package documentation explains why.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class Rfc9421RequestSigner implements RequestSigner {

    private static final String LABEL = "sig1";

    private final Clock clock;

    @Autowired
    Rfc9421RequestSigner() {
        this(Clock.systemUTC());
    }

    Rfc9421RequestSigner(Clock clock) {
        this.clock = clock;
    }

    @Override
    public HttpHeaders sign(String method, URI target, byte[] body, SigningKey key) {
        // Checked first so that a bad URL always gets this error.
        var authority = SignedRequests.authority(target);
        var added = new HttpHeaders();
        var names = new ArrayList<String>();
        var lines = new ArrayList<String>();
        names.add(Rfc9421SignatureScheme.METHOD);
        lines.add(method);
        names.add(Rfc9421SignatureScheme.TARGET_URI);
        // Built from what the request line and Host header will say, which is what a receiver rebuilds it from, rather
        // than from the URL as it was given, which can have an uppercase host or a default port.
        lines.add(target.getScheme().toLowerCase(Locale.ROOT) + "://" + authority + SignedRequests.requestTarget(target));
        if (SignedRequests.needsDigest(method, body)) {
            added.set(Rfc9421SignatureScheme.CONTENT_DIGEST,
                    "sha-256=" + StructuredFields.serialize(new Item(Digests.sha256(body), new LinkedHashMap<>())));
            names.add(Rfc9421SignatureScheme.CONTENT_DIGEST_COMPONENT);
            lines.add(added.getFirst(Rfc9421SignatureScheme.CONTENT_DIGEST));
        }

        SequencedMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("created", clock.instant().getEpochSecond());
        parameters.put("keyid", key.keyId());
        parameters.put("alg", Rfc9421SignatureScheme.ALGORITHM);
        var components = new InnerList(
                names.stream().map(name -> new Item(name, new LinkedHashMap<>())).toList(), parameters);
        var signatureParams = StructuredFields.serialize(components);

        var base = new StringBuilder();
        for (var i = 0; i < names.size(); i++) {
            base.append('"').append(names.get(i)).append("\": ").append(lines.get(i)).append('\n');
        }
        base.append("\"@signature-params\": ").append(signatureParams);
        var signature = SignatureAlgorithm.RSA_SHA256.sign(
                key.privateKey(), base.toString().getBytes(StandardCharsets.ISO_8859_1));

        added.set(Rfc9421SignatureScheme.SIGNATURE_INPUT, LABEL + "=" + signatureParams);
        added.set(Rfc9421SignatureScheme.SIGNATURE,
                LABEL + "=" + StructuredFields.serialize(new Item(signature, new LinkedHashMap<>())));
        return added;
    }
}
