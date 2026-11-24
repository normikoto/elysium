package dev.biribiri.elysium.push.internal;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class VapidTokensTests {

    @Test
    void isSignedForTheEndpointsOrigin() throws Exception {
        var keys = P256.generate();
        var now = Instant.parse("2026-01-01T00:00:00Z");

        var token = VapidTokens.create(URI.create("https://push.example:8443/send/abc?x=1"),
                URI.create("https://elysium.example/"), keys.getPrivate(), now);

        var parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        var header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        var claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat((String) JsonPath.read(header, "$.alg")).isEqualTo("ES256");
        assertThat((String) JsonPath.read(claims, "$.aud")).isEqualTo("https://push.example:8443");
        assertThat((String) JsonPath.read(claims, "$.sub")).isEqualTo("https://elysium.example/");
        assertThat(((Number) JsonPath.read(claims, "$.exp")).longValue())
                .isEqualTo(now.plus(VapidTokens.LIFETIME).getEpochSecond());

        var signature = Base64.getUrlDecoder().decode(parts[2]);
        assertThat(signature).hasSize(64);
        var verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(keys.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(signature)).isTrue();
    }
}
