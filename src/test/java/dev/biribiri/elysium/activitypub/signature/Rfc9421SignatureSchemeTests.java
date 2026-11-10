package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException.Failure;
import dev.biribiri.elysium.activitypub.signature.StructuredFields.InnerList;
import dev.biribiri.elysium.actor.InstanceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.HttpHeaders;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Rfc9421SignatureSchemeTests {

    private static final String KEY_ID = "https://remote.example/users/bob#main-key";
    private static final String ANY_SIGNATURE = "sig1=:c2lnbmF0dXJl:";
    private static final String PARAMS = ";created=1793707200;keyid=\"" + KEY_ID + "\"";

    /**
     * The request that RFC 9421's examples in Appendix B.2 sign, with its {@code Content-Digest} on one line.
     */
    private static final String RFC_DIGEST =
            "sha-512=:WZDPaVn/7XgHaAy8pmojAkGWoRx2UFChF41A2svX+TaPm+AbwAgBWnrIiYllu7BNNyealdVLvRwEmTHWXvJwew==:";
    private static final byte[] RFC_BODY = "{\"hello\": \"world\"}".getBytes(StandardCharsets.UTF_8);

    /**
     * RFC 9421's test-key-rsa from Appendix B.1.1, which the RFC gives as PKCS #1, converted with
     * {@code openssl rsa -RSAPublicKey_in -pubout}.
     */
    private static final String RFC_TEST_KEY_RSA = """
            -----BEGIN PUBLIC KEY-----
            MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAhAKYdtoeoy8zcAcR874L
            8cnZxKzAGwd7v36APp7Pv6Q2jdsPBRrwWEBnez6d0UDKDwGbc6nxfEXAy5mbhgaj
            zrw3MOEt8uA5txSKobBpKDeBLOsdJKFqMGmXCQvEG7YemcxDTRPxAleIAgYYRjTS
            d/QBwVW9OwNFhekro3RtlinV0a75jfZgkne/YiktSvLG34lw2zqXBDTC5NHROUqG
            TlML4PlNZS5Ri2U4aCNx2rUPRcKIlE0PuKxI4T+HIaFpv8+rdV6eUgOrB2xeI1dS
            FFn/nnv5OoZJEIB+VmuKn3DCUcCZSFlQPSXSfBDiUGhwOw76WuSSsf1D4b/vLoJ1
            0wIDAQAB
            -----END PUBLIC KEY-----
            """;

    private final Rfc9421SignatureScheme scheme =
            new Rfc9421SignatureScheme(new InstanceProperties(URI.create("https://example.test")));

    @Test
    void readsAPostSignedByOpenSsl() {
        var claim = scheme.parse(post(SignatureFixtures.RFC_9421_POST_INPUT,
                "sig1=:" + SignatureFixtures.RFC_9421_POST_SIGNATURE + ":"));

        assertThat(claim.keyId()).isEqualTo(KEY_ID);
        assertThat(claim.created()).isEqualTo(Instant.ofEpochSecond(SignatureFixtures.CREATED));
        assertThat(claim.expires()).isNull();
        assertThat(verifies(claim)).isTrue();
    }

    @Test
    void readsAGetSignedByOpenSslWithAuthorityPathAndQuery() {
        var claim = scheme.parse(get("/users/alice?page=1", SignatureFixtures.RFC_9421_GET_INPUT,
                "sig1=:" + SignatureFixtures.RFC_9421_GET_SIGNATURE + ":"));

        assertThat(verifies(claim)).isTrue();
    }

    /**
     * RFC 9421's own example, so that the signature base isn't only checked against this project's reading of the RFC.
     * Its signature is RSA-PSS, which this server doesn't check, so only the base is compared.
     */
    @Test
    void buildsTheSameSignatureBaseAsTheRfcsFullCoverageExample() {
        var headers = new HttpHeaders();
        headers.set("Host", "example.com");
        headers.set("Date", "Tue, 20 Apr 2021 02:07:55 GMT");
        headers.set("Content-Type", "application/json");
        headers.set("Content-Digest", RFC_DIGEST);
        headers.set("Content-Length", "18");
        headers.set("Signature-Input", "sig-b23=(\"date\" \"@method\" \"@path\" \"@query\" \"@authority\" "
                + "\"content-type\" \"content-digest\" \"content-length\");created=1618884473;keyid=\"test-key-rsa-pss\"");
        headers.set("Signature", "sig-b23=:c2lnbmF0dXJl:");
        var request = new HttpRequestData("POST", "/foo?param=Value&Pet=dog", headers, RFC_BODY);

        var claim = new Rfc9421SignatureScheme(new InstanceProperties(URI.create("https://example.com"))).parse(request);

        assertThat(base(claim)).isEqualTo("""
                "date": Tue, 20 Apr 2021 02:07:55 GMT
                "@method": POST
                "@path": /foo
                "@query": ?param=Value&Pet=dog
                "@authority": example.com
                "content-type": application/json
                "content-digest": %s
                "content-length": 18
                "@signature-params": ("date" "@method" "@path" "@query" "@authority" "content-type" \
                "content-digest" "content-length");created=1618884473;keyid="test-key-rsa-pss\"""".formatted(RFC_DIGEST));
    }

    /**
     * The RFC's proxy example in section 4.3, whose second signature is a real rsa-v1_5-sha256 one. It doesn't cover the
     * query, so this server wouldn't accept it, but the base built here has to match what the RFC's key signed.
     */
    @Test
    void buildsASignatureBaseThatTheRfcsRsaExampleVerifies() {
        var headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("Content-Length", "18");
        headers.set("Forwarded", "for=192.0.2.123;host=example.com;proto=https");
        headers.set("Content-Digest", RFC_DIGEST);
        var request = new HttpRequestData("POST", "/foo?param=Value&Pet=dog", headers, RFC_BODY);
        var input = (InnerList) StructuredFields.parseDictionary("sig1=(\"@method\" \"@authority\" \"@path\" "
                + "\"content-digest\" \"content-type\" \"content-length\");created=1618884475;keyid=\"test-key-ecc-p256\", "
                + "proxy_sig=(\"@method\" \"@authority\" \"@path\" \"content-digest\" \"content-type\" \"content-length\" "
                + "\"forwarded\");created=1618884480;keyid=\"test-key-rsa\";alg=\"rsa-v1_5-sha256\";expires=1618884540")
                .get("proxy_sig");
        var signature = Base64.getDecoder().decode(
                "S6ZzPXSdAMOPjN/6KXfXWNO/f7V6cHm7BXYUh3YD/fRad4BCaRZxP+JH+8XY1I6+8Cy+CM5g92iHgxtRPz+MjniOaYmdkDcnL9cCpXJ"
                        + "leXsOckpURl49GwiyUpZ10KHgOEe11sx3G2gxI8S0jnxQB+Pu68U9vVcasqOWAEObtNKKZd8tSFu7LB5YAv0RAGhB8tmpv7sFnI"
                        + "m9y+7X5kXQfi8NMaZaA8i2ZHwpBdg7a6CMfwnnrtflzvZdXAsD3LH2TwevU+/PBPv0B6NMNk93wUs/vfJvye+YuI87HU38lZHow"
                        + "tznbLVdp770I6VHR6WfgS9ddzirrswsE1w5o0LV/g==");
        var proxied = new Rfc9421SignatureScheme(
                new InstanceProperties(URI.create("https://origin.host.internal.example")));

        var base = proxied.signatureBase(request, input);

        assertThat(SignatureAlgorithm.RSA_SHA256.verify(PublicKeyPem.parse(RFC_TEST_KEY_RSA), base, signature)).isTrue();
    }

    @Test
    void comesBeforeTheCavageScheme() {
        var instance = new InstanceProperties(URI.create("https://example.test"));
        var cavage = new CavageSignatureScheme(instance);
        var schemes = new ArrayList<SignatureScheme>(List.of(cavage, scheme));

        AnnotationAwareOrderComparator.sort(schemes);

        assertThat(schemes).containsExactly(scheme, cavage);
    }

    @Test
    void appliesOnlyToRequestsWithBothFields() {
        var both = post(SignatureFixtures.RFC_9421_POST_INPUT, ANY_SIGNATURE);
        var inputOnly = new HttpHeaders();
        inputOnly.set("Signature-Input", SignatureFixtures.RFC_9421_POST_INPUT);
        var signatureOnly = new HttpHeaders();
        signatureOnly.set("Signature", ANY_SIGNATURE);

        assertThat(scheme.appliesTo(both)).isTrue();
        assertThat(scheme.appliesTo(new HttpRequestData("POST", "/", inputOnly, new byte[0]))).isFalse();
        assertThat(scheme.appliesTo(new HttpRequestData("POST", "/", signatureOnly, new byte[0]))).isFalse();
    }

    @Test
    void usesThisServersNameAndSchemeWhateverTheRequestSays() {
        var headers = new HttpHeaders();
        headers.set("Host", "other.example");
        headers.set("Signature-Input", "sig1=(\"@method\" \"@target-uri\" \"@authority\" \"@scheme\")" + PARAMS);
        headers.set("Signature", ANY_SIGNATURE);

        var claim = scheme.parse(new HttpRequestData("GET", "/users/alice", headers, new byte[0]));

        assertThat(base(claim)).startsWith("""
                "@method": GET
                "@target-uri": https://example.test/users/alice
                "@authority": example.test
                "@scheme": https
                """);
    }

    @Test
    void includesThePortOfANonDefaultBaseUrl() {
        var withPort = new Rfc9421SignatureScheme(new InstanceProperties(URI.create("https://example.test:8443")));

        var claim = withPort.parse(get("/a", "sig1=(\"@method\" \"@target-uri\")" + PARAMS, ANY_SIGNATURE));

        assertThat(base(claim)).contains("\"@target-uri\": https://example.test:8443/a\n");
    }

    @Test
    void derivesRequestTargetPathAndAnEmptyQuery() {
        var claim = scheme.parse(get("/a%7Eb/c",
                "sig1=(\"@method\" \"@authority\" \"@request-target\" \"@path\" \"@query\")" + PARAMS, ANY_SIGNATURE));

        assertThat(base(claim)).contains("\"@request-target\": /a%7Eb/c\n\"@path\": /a%7Eb/c\n\"@query\": ?\n");
    }

    @Test
    void joinsRepeatedHeadersAndStripsThem() {
        var headers = new HttpHeaders();
        headers.add("X-Thing", " a ");
        headers.add("X-Thing", "b");
        headers.set("Signature-Input", "sig1=(\"@method\" \"@target-uri\" \"x-thing\")" + PARAMS);
        headers.set("Signature", ANY_SIGNATURE);

        var claim = scheme.parse(new HttpRequestData("GET", "/", headers, new byte[0]));

        assertThat(base(claim)).contains("\"x-thing\": a, b\n");
    }

    @Test
    void reserializesTheParametersInCanonicalForm() {
        var claim = scheme.parse(get("/a", "sig1=(  \"@method\"   \"@target-uri\" )" + PARAMS, ANY_SIGNATURE));

        assertThat(base(claim)).endsWith("\"@signature-params\": (\"@method\" \"@target-uri\")" + PARAMS);
    }

    @Test
    void readsExpires() {
        var claim = scheme.parse(get("/a", "sig1=(\"@method\" \"@target-uri\")" + PARAMS + ";expires=1793707500",
                ANY_SIGNATURE));

        assertThat(claim.expires()).isEqualTo(Instant.ofEpochSecond(1793707500));
    }

    @Test
    void usesTheFirstSignatureThatIsGoodEnough() {
        var input = "weak=(\"@method\")" + PARAMS + ", " + SignatureFixtures.RFC_9421_POST_INPUT;
        var signatures = "weak=:c2lnbmF0dXJl:, sig1=:" + SignatureFixtures.RFC_9421_POST_SIGNATURE + ":";

        assertThat(verifies(scheme.parse(post(input, signatures)))).isTrue();
    }

    @Test
    void readsFieldsSplitAcrossSeveralLines() {
        var headers = postHeaders();
        headers.add("Signature-Input", "weak=(\"@method\")" + PARAMS);
        headers.add("Signature-Input", SignatureFixtures.RFC_9421_POST_INPUT);
        headers.add("Signature", "weak=:c2lnbmF0dXJl:");
        headers.add("Signature", "sig1=:" + SignatureFixtures.RFC_9421_POST_SIGNATURE + ":");

        assertThat(verifies(scheme.parse(postRequest(headers)))).isTrue();
    }

    @Test
    void explainsTheFirstFailureIfNoSignatureIsGoodEnough() {
        assertThatThrownBy(() -> scheme.parse(get("/a",
                "first=(\"@target-uri\")" + PARAMS + ", second=(\"@method\")" + PARAMS,
                "first=:c2ln:, second=:c2ln:")))
                .isInstanceOfSatisfying(SignatureVerificationException.class, e -> {
                    assertThat(e.failure()).isEqualTo(Failure.REJECTED);
                    assertThat(e.getMessage()).contains("@method");
                });
    }

    @Test
    void requiresTheMethod() {
        assertRejected(get("/a", "sig1=(\"@target-uri\")" + PARAMS, ANY_SIGNATURE));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "(\"@method\" \"@path\" \"@query\")",
            "(\"@method\" \"@authority\")",
            "(\"@method\" \"@authority\" \"@path\")",
            "(\"@method\" \"@request-target\")",
    })
    void requiresTheServerAndTheWholeTarget(String components) {
        assertRejected(get("/a?b=c", "sig1=" + components + PARAMS, ANY_SIGNATURE));
    }

    @Test
    void requiresAContentDigestOnPost() {
        assertRejected(post("sig1=(\"@method\" \"@target-uri\")" + PARAMS, ANY_SIGNATURE));
    }

    @Test
    void requiresAContentDigestOnAGetWithABody() {
        var headers = new HttpHeaders();
        headers.set("Signature-Input", "sig1=(\"@method\" \"@target-uri\")" + PARAMS);
        headers.set("Signature", ANY_SIGNATURE);

        assertRejected(new HttpRequestData("GET", "/a", headers, body()));
    }

    @Test
    void requiresACreatedTime() {
        assertRejected(get("/a", "sig1=(\"@method\" \"@target-uri\");keyid=\"" + KEY_ID + "\"", ANY_SIGNATURE));
    }

    @Test
    void requiresAKeyId() {
        assertRejected(get("/a", "sig1=(\"@method\" \"@target-uri\");created=1793707200", ANY_SIGNATURE));
    }

    @Test
    void requiresASignatureForTheLabel() {
        assertRejected(get("/a", "sig1=(\"@method\" \"@target-uri\")" + PARAMS, "other=:c2ln:"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rsa-pss-sha512", "ed25519", "hmac-sha256", "ecdsa-p256-sha256"})
    void rejectsOtherAlgorithms(String algorithm) {
        assertRejected(get("/a", "sig1=(\"@method\" \"@target-uri\")" + PARAMS + ";alg=\"" + algorithm + "\"",
                ANY_SIGNATURE));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"@method\";req",
            "\"x-thing\";sf",
            "\"@query-param\";name=\"a\"",
            "\"@status\"",
            "\"@unknown\"",
            "\"X-Thing\"",
    })
    void rejectsComponentsItDoesNotSupport(String component) {
        var headers = new HttpHeaders();
        headers.set("X-Thing", "a");
        headers.set("Signature-Input", "sig1=(\"@method\" \"@target-uri\" " + component + ")" + PARAMS);
        headers.set("Signature", ANY_SIGNATURE);

        assertRejected(new HttpRequestData("GET", "/a?a=b", headers, new byte[0]));
    }

    @Test
    void rejectsACoveredHeaderThatIsMissing() {
        assertRejected(get("/a", "sig1=(\"@method\" \"@target-uri\" \"x-missing\")" + PARAMS, ANY_SIGNATURE));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "sig1=(\"@method\" \"@target-uri\" \"@method\")" + PARAMS,
            "sig1=\"@method\"" + PARAMS,
            "sig1=(\"@method\" @target-uri)" + PARAMS,
            "sig1=(\"@method\" \"@target-uri\");created=\"1793707200\";keyid=\"k\"",
            "sig1=(\"@method\" \"@target-uri\");created=1793707200;keyid=k",
            "sig1=(\"@method\" \"@target-uri\")" + PARAMS + ";expires=1.5",
    })
    void rejectsMalformedInputs(String input) {
        assertFails(get("/a", input, ANY_SIGNATURE), Failure.MALFORMED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sig1=\"c2lnbmF0dXJl\"", "sig1=:c2lnbmF0dXJl", "sig1=:c2lnbmF0dXJl:,"})
    void rejectsMalformedSignatures(String signature) {
        assertFails(get("/a", "sig1=(\"@method\" \"@target-uri\")" + PARAMS, signature), Failure.MALFORMED);
    }

    @Test
    void acceptsASha512ContentDigest() {
        var claim = scheme.parse(postWithDigest("sha-512=:" + SignatureFixtures.BODY_SHA512 + ":"));

        assertThat(claim).isNotNull();
    }

    @Test
    void ignoresDigestAlgorithmsItDoesNotKnow() {
        var claim = scheme.parse(postWithDigest("md5=:YWJj:, sha-256=:" + SignatureFixtures.BODY_DIGEST + ":"));

        assertThat(claim).isNotNull();
    }

    @Test
    void rejectsAContentDigestThatDoesNotMatchTheBody() {
        var other = Base64.getEncoder().encodeToString(new byte[32]);

        assertRejected(postWithDigest("sha-256=:" + other + ":"));
    }

    @Test
    void rejectsAnyKnownDigestThatDoesNotMatch() {
        var other = Base64.getEncoder().encodeToString(new byte[64]);

        assertRejected(postWithDigest("sha-256=:" + SignatureFixtures.BODY_DIGEST + ":, sha-512=:" + other + ":"));
    }

    @Test
    void rejectsAContentDigestWithoutAKnownAlgorithm() {
        assertRejected(postWithDigest("md5=:YWJj:"));
    }

    @Test
    void rejectsAContentDigestThatIsNotAByteSequence() {
        assertFails(postWithDigest("sha-256=\"" + SignatureFixtures.BODY_DIGEST + "\""), Failure.MALFORMED);
    }

    private HttpRequestData postWithDigest(String digest) {
        var headers = new HttpHeaders();
        headers.set("Content-Digest", digest);
        headers.set("Signature-Input", "sig1=(\"@method\" \"@target-uri\" \"content-digest\")" + PARAMS);
        headers.set("Signature", ANY_SIGNATURE);
        return postRequest(headers);
    }

    private static boolean verifies(SignatureClaim claim) {
        return claim.algorithm().verify(SignatureFixtures.publicKey(), claim.signatureBase(), claim.signature());
    }

    private void assertRejected(HttpRequestData request) {
        assertFails(request, Failure.REJECTED);
    }

    private void assertFails(HttpRequestData request, Failure failure) {
        assertThatThrownBy(() -> scheme.parse(request))
                .isInstanceOfSatisfying(SignatureVerificationException.class,
                        e -> assertThat(e.failure()).isEqualTo(failure));
    }

    private static String base(SignatureClaim claim) {
        return new String(claim.signatureBase(), StandardCharsets.ISO_8859_1);
    }

    private static HttpRequestData get(String target, String input, String signature) {
        var headers = new HttpHeaders();
        headers.set("Signature-Input", input);
        headers.set("Signature", signature);
        return new HttpRequestData("GET", target, headers, new byte[0]);
    }

    private static HttpRequestData post(String input, String signature) {
        var headers = postHeaders();
        headers.set("Signature-Input", input);
        headers.set("Signature", signature);
        return postRequest(headers);
    }

    private static HttpHeaders postHeaders() {
        var headers = new HttpHeaders();
        headers.set("Content-Digest", "sha-256=:" + SignatureFixtures.BODY_DIGEST + ":");
        return headers;
    }

    private static HttpRequestData postRequest(HttpHeaders headers) {
        return new HttpRequestData("POST", "/users/alice/inbox", headers, body());
    }

    private static byte[] body() {
        return SignatureFixtures.BODY.getBytes(StandardCharsets.UTF_8);
    }
}
