package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException.Failure;
import dev.biribiri.elysium.actor.InstanceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CavageSignatureSchemeTests {

    private static final String KEY_ID = "https://remote.example/users/bob#main-key";
    private static final String POST_HEADERS = "(request-target) host date digest";
    private static final String ANY_SIGNATURE = "c2lnbmF0dXJl";

    private final CavageSignatureScheme scheme =
            new CavageSignatureScheme(new InstanceProperties(URI.create("https://example.test")));

    @Test
    void readsARequestSignedByOpenSsl() {
        var claim = scheme.parse(post(signature("rsa-sha256", POST_HEADERS, SignatureFixtures.POST_SIGNATURE)));

        assertThat(claim.keyId()).isEqualTo(KEY_ID);
        assertThat(claim.created()).isEqualTo(Instant.ofEpochSecond(SignatureFixtures.CREATED));
        assertThat(claim.expires()).isNull();
        assertThat(claim.algorithm().verify(SignatureFixtures.publicKey(), claim.signatureBase(), claim.signature()))
                .isTrue();
    }

    @Test
    void includesTheQueryInTheRequestTarget() {
        var headers = headers(signature("hs2019", "(request-target) host date", SignatureFixtures.GET_SIGNATURE));
        headers.set(HttpHeaders.DATE, SignatureFixtures.DATE);

        var claim = scheme.parse(new HttpRequestData("GET", "/users/alice?page=1", headers, new byte[0]));

        assertThat(claim.algorithm().verify(SignatureFixtures.publicKey(), claim.signatureBase(), claim.signature()))
                .isTrue();
    }

    @Test
    void readsAndSignsCreatedUnderHs2019() {
        var header = ("keyId=\"%s\",algorithm=\"hs2019\",created=%d,"
                + "headers=\"(request-target) host (created)\",signature=\"%s\"")
                .formatted(KEY_ID, SignatureFixtures.CREATED, SignatureFixtures.CREATED_SIGNATURE);

        var claim = scheme.parse(new HttpRequestData("GET", "/users/alice", headers(header), new byte[0]));

        assertThat(claim.created()).isEqualTo(Instant.ofEpochSecond(SignatureFixtures.CREATED));
        assertThat(claim.algorithm().verify(SignatureFixtures.publicKey(), claim.signatureBase(), claim.signature()))
                .isTrue();
    }

    @Test
    void treatsAMissingAlgorithmAsHs2019() {
        var header = "keyId=\"%s\",created=%d,headers=\"(request-target) host (created)\",signature=\"%s\""
                .formatted(KEY_ID, SignatureFixtures.CREATED, SignatureFixtures.CREATED_SIGNATURE);

        assertThat(scheme.parse(new HttpRequestData("GET", "/users/alice", headers(header), new byte[0])).created())
                .isEqualTo(Instant.ofEpochSecond(SignatureFixtures.CREATED));
    }

    @Test
    void usesThisServersNameForHostWhateverTheHeaderSays() {
        var headers = headers(signature("rsa-sha256", POST_HEADERS, ANY_SIGNATURE));
        headers.set(HttpHeaders.HOST, "other.example");
        addPostHeaders(headers);

        assertThat(base(scheme.parse(request("POST", headers)))).contains("host: example.test\n");
    }

    @Test
    void includesThePortOfANonDefaultBaseUrlInHost() {
        var withPort = new CavageSignatureScheme(new InstanceProperties(URI.create("https://example.test:8443")));
        var headers = headers(signature("rsa-sha256", POST_HEADERS, ANY_SIGNATURE));
        // nginx's usual "proxy_set_header Host $host" drops the port.
        headers.set(HttpHeaders.HOST, "example.test");
        addPostHeaders(headers);

        assertThat(base(withPort.parse(request("POST", headers)))).contains("host: example.test:8443\n");
    }

    @Test
    void lowercasesItemNamesAndKeepsTheirOrder() {
        var headers = headers(signature("rsa-sha256", "HOST Date (Request-Target) Digest", ANY_SIGNATURE));
        addPostHeaders(headers);

        assertThat(base(scheme.parse(request("POST", headers)))).isEqualTo("""
                host: example.test
                date: %s
                (request-target): post /users/alice/inbox
                digest: SHA-256=%s""".formatted(SignatureFixtures.DATE, SignatureFixtures.BODY_DIGEST));
    }

    @Test
    void joinsRepeatedHeadersAndKeepsEmptyOnes() {
        var headers = headers(signature("rsa-sha256", POST_HEADERS + " x-many x-empty", ANY_SIGNATURE));
        addPostHeaders(headers);
        headers.add("X-Many", " one ");
        headers.add("X-Many", "two");
        headers.add("X-Empty", "");

        assertThat(base(scheme.parse(request("POST", headers)))).endsWith("\nx-many: one, two\nx-empty: ");
    }

    @Test
    void keepsTheRawTargetWithoutDecodingIt() {
        var headers = headers(signature("rsa-sha256", POST_HEADERS, ANY_SIGNATURE));
        addPostHeaders(headers);
        var request = new HttpRequestData("POST", "/users/a%20b?x=%2F", headers, body());

        assertThat(base(scheme.parse(request))).startsWith("(request-target): post /users/a%20b?x=%2F\n");
    }

    @Test
    void appliesOnlyToRequestsWithoutAnRfc9421Input() {
        var cavage = headers("keyId=\"k\"");
        var rfc9421 = headers("sig1=:c2ln:");
        rfc9421.set("Signature-Input", "sig1=(\"@method\");created=1;keyid=\"k\"");

        assertThat(scheme.appliesTo(request("POST", cavage))).isTrue();
        assertThat(scheme.appliesTo(request("POST", rfc9421))).isFalse();
        assertThat(scheme.appliesTo(request("POST", new HttpHeaders()))).isFalse();
    }

    @Test
    void requiresTheRequestTarget() {
        assertRejected(post(signature("rsa-sha256", "host date digest", ANY_SIGNATURE)));
    }

    @Test
    void requiresHostEvenOnPost() {
        assertRejected(post(signature("rsa-sha256", "(request-target) date digest", ANY_SIGNATURE)));
    }

    @Test
    void requiresAHostOnGet() {
        var headers = headers(signature("rsa-sha256", "(request-target) date", ANY_SIGNATURE));
        headers.set(HttpHeaders.DATE, SignatureFixtures.DATE);

        assertRejected(new HttpRequestData("GET", "/users/alice", headers, new byte[0]));
    }

    @Test
    void requiresATime() {
        assertRejected(post(signature("rsa-sha256", "(request-target) host digest", ANY_SIGNATURE)));
    }

    @Test
    void requiresADigestOnPost() {
        assertRejected(post(signature("rsa-sha256", "(request-target) host date", ANY_SIGNATURE)));
    }

    @Test
    void requiresADigestOnOtherMethodsThatMayHaveBodies() {
        var headers = headers(signature("rsa-sha256", "(request-target) host date", ANY_SIGNATURE));
        headers.set(HttpHeaders.DATE, SignatureFixtures.DATE);

        assertRejected(new HttpRequestData("PUT", "/users/alice", headers, new byte[0]));
    }

    @Test
    void requiresADigestOnAGetWithABody() {
        var headers = headers(signature("rsa-sha256", "(request-target) host date", ANY_SIGNATURE));
        headers.set(HttpHeaders.DATE, SignatureFixtures.DATE);

        assertRejected(new HttpRequestData("GET", "/users/alice", headers, body()));
    }

    @Test
    void doesNotRequireADigestOnAGetWithoutABody() {
        var headers = headers(signature("rsa-sha256", "(request-target) host date", ANY_SIGNATURE));
        headers.set(HttpHeaders.DATE, SignatureFixtures.DATE);

        assertThat(scheme.parse(new HttpRequestData("GET", "/users/alice", headers, new byte[0]))).isNotNull();
    }

    @Test
    void defaultsToCoveringOnlyCreatedWhichIsNotEnough() {
        var header = "keyId=\"%s\",created=%d,signature=\"%s\""
                .formatted(KEY_ID, SignatureFixtures.CREATED, ANY_SIGNATURE);

        assertRejected(new HttpRequestData("GET", "/users/alice", headers(header), new byte[0]));
    }

    @ParameterizedTest
    @ValueSource(strings = {"(request-target) host (created) digest", "(request-target) host date digest (expires)"})
    void rejectsCreatedAndExpiresWithRsaSha256(String covered) {
        var header = "keyId=\"%s\",algorithm=\"rsa-sha256\",created=%d,expires=%d,headers=\"%s\",signature=\"%s\""
                .formatted(KEY_ID, SignatureFixtures.CREATED, SignatureFixtures.CREATED + 60, covered, ANY_SIGNATURE);

        assertRejected(post(header));
    }

    @Test
    void rejectsUnknownPseudoHeaders() {
        assertRejected(post(signature("rsa-sha256", POST_HEADERS + " (other)", ANY_SIGNATURE)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rsa-sha1", "hmac-sha256", "ecdsa-sha256", "ed25519", "none"})
    void rejectsOtherAlgorithms(String algorithm) {
        assertRejected(post(signature(algorithm, POST_HEADERS, ANY_SIGNATURE)));
    }

    /**
     * Mastodon reads these unsigned parameters, which would let anyone send an old request again with a new time.
     */
    @Test
    void ignoresACreatedParameterTheSignatureDoesNotCover() {
        var header = "keyId=\"%s\",algorithm=\"hs2019\",created=%d,headers=\"%s\",signature=\"%s\""
                .formatted(KEY_ID, SignatureFixtures.CREATED + 86400, POST_HEADERS, ANY_SIGNATURE);

        assertThat(scheme.parse(post(header)).created()).isEqualTo(Instant.ofEpochSecond(SignatureFixtures.CREATED));
    }

    @Test
    void ignoresAnExpiresParameterTheSignatureDoesNotCover() {
        var header = "keyId=\"%s\",algorithm=\"hs2019\",expires=%d,headers=\"%s\",signature=\"%s\""
                .formatted(KEY_ID, SignatureFixtures.CREATED + 86400, POST_HEADERS, ANY_SIGNATURE);

        assertThat(scheme.parse(post(header)).expires()).isNull();
    }

    @Test
    void readsACoveredExpiresWithAFractionAndKeepsItsTextInTheBase() {
        var header = "keyId=\"%s\",created=%d,expires=%d.50,headers=\"%s\",signature=\"%s\"".formatted(
                KEY_ID, SignatureFixtures.CREATED, SignatureFixtures.CREATED + 60,
                "(request-target) host (created) (expires)", ANY_SIGNATURE);

        var claim = scheme.parse(new HttpRequestData("GET", "/users/alice", headers(header), new byte[0]));

        assertThat(claim.expires()).isEqualTo(Instant.ofEpochSecond(SignatureFixtures.CREATED + 60));
        assertThat(base(claim)).endsWith("(expires): " + (SignatureFixtures.CREATED + 60) + ".50");
    }

    @ParameterizedTest
    @ValueSource(strings = {"soon", "-1", "1.5", "99999999999999999999"})
    void rejectsAnInvalidCreated(String created) {
        var header = "keyId=\"%s\",created=\"%s\",headers=\"(request-target) host (created)\",signature=\"%s\""
                .formatted(KEY_ID, created, ANY_SIGNATURE);

        assertFails(new HttpRequestData("GET", "/users/alice", headers(header), new byte[0]), Failure.MALFORMED);
    }

    @Test
    void rejectsACoveredCreatedThatIsMissing() {
        var header = "keyId=\"%s\",headers=\"(request-target) host (created)\",signature=\"%s\""
                .formatted(KEY_ID, ANY_SIGNATURE);

        assertRejected(new HttpRequestData("GET", "/users/alice", headers(header), new byte[0]));
    }

    @Test
    void rejectsAnInvalidDate() {
        var headers = headers(signature("rsa-sha256", POST_HEADERS, ANY_SIGNATURE));
        addPostHeaders(headers);
        headers.set(HttpHeaders.DATE, "yesterday");

        assertFails(request("POST", headers), Failure.MALFORMED);
    }

    @Test
    void rejectsRepeatedDates() {
        var headers = headers(signature("rsa-sha256", POST_HEADERS, ANY_SIGNATURE));
        addPostHeaders(headers);
        headers.add(HttpHeaders.DATE, SignatureFixtures.DATE);

        assertFails(request("POST", headers), Failure.MALFORMED);
    }

    @Test
    void rejectsACoveredHeaderThatIsMissing() {
        assertRejected(post(signature("rsa-sha256", POST_HEADERS + " content-type", ANY_SIGNATURE)));
    }

    @Test
    void rejectsACoveredDateThatIsMissing() {
        var headers = headers(signature("rsa-sha256", POST_HEADERS, ANY_SIGNATURE));
        headers.set("Digest", "SHA-256=" + SignatureFixtures.BODY_DIGEST);

        assertRejected(request("POST", headers));
    }

    @Test
    void rejectsRepeatedSignatureHeaders() {
        var headers = headers(signature("rsa-sha256", POST_HEADERS, ANY_SIGNATURE));
        headers.add("Signature", signature("rsa-sha256", POST_HEADERS, ANY_SIGNATURE));
        addPostHeaders(headers);

        assertFails(request("POST", headers), Failure.MALFORMED);
    }

    @Test
    void requiresAKeyId() {
        assertRejected(post("signature=\"%s\",headers=\"%s\"".formatted(ANY_SIGNATURE, POST_HEADERS)));
    }

    @Test
    void requiresASignature() {
        assertRejected(post("keyId=\"%s\",headers=\"%s\"".formatted(KEY_ID, POST_HEADERS)));
    }

    @Test
    void rejectsASignatureThatIsNotBase64() {
        assertFails(post(signature("rsa-sha256", POST_HEADERS, "not base64!")), Failure.MALFORMED);
    }

    @Test
    void rejectsAnEmptyHeadersList() {
        assertFails(post(signature("rsa-sha256", " ", ANY_SIGNATURE)), Failure.MALFORMED);
    }

    @Test
    void acceptsDigestNamesInAnyCase() {
        assertThat(scheme.parse(postWithDigest("sha-256=" + SignatureFixtures.BODY_DIGEST))).isNotNull();
    }

    /**
     * This body's digest ends in {@code =}, which also separates a digest's name from its value.
     */
    @Test
    void keepsPaddingInDigests() {
        assertThat(SignatureFixtures.BODY_DIGEST).endsWith("=");
        assertThat(scheme.parse(postWithDigest("SHA-256=" + SignatureFixtures.BODY_DIGEST))).isNotNull();
    }

    @Test
    void findsSha256AmongSeveralDigests() {
        assertThat(scheme.parse(postWithDigest("MD5=abc=, SHA-256=" + SignatureFixtures.BODY_DIGEST))).isNotNull();
    }

    @Test
    void rejectsADigestThatDoesNotMatchTheBody() {
        var other = Base64.getEncoder().encodeToString(new byte[32]);

        assertRejected(postWithDigest("SHA-256=" + other));
    }

    @Test
    void rejectsADigestWithoutSha256() {
        assertRejected(postWithDigest("SHA-512=" + SignatureFixtures.BODY_DIGEST));
    }

    @Test
    void rejectsADigestThatIsNotBase64() {
        assertFails(postWithDigest("SHA-256=not base64!"), Failure.MALFORMED);
    }

    @Test
    void rejectsADigestThatIsMissing() {
        var headers = headers(signature("rsa-sha256", POST_HEADERS, ANY_SIGNATURE));
        headers.set(HttpHeaders.DATE, SignatureFixtures.DATE);

        assertRejected(request("POST", headers));
    }

    private HttpRequestData postWithDigest(String digest) {
        var headers = headers(signature("rsa-sha256", POST_HEADERS, ANY_SIGNATURE));
        headers.set(HttpHeaders.DATE, SignatureFixtures.DATE);
        headers.set("Digest", digest);
        return request("POST", headers);
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

    private static String signature(String algorithm, String covered, String signature) {
        return "keyId=\"%s\",algorithm=\"%s\",headers=\"%s\",signature=\"%s\""
                .formatted(KEY_ID, algorithm, covered, signature);
    }

    private static HttpRequestData post(String signatureHeader) {
        var headers = headers(signatureHeader);
        addPostHeaders(headers);
        return request("POST", headers);
    }

    private static HttpRequestData request(String method, HttpHeaders headers) {
        return new HttpRequestData(method, "/users/alice/inbox", headers, body());
    }

    private static HttpHeaders headers(String signatureHeader) {
        var headers = new HttpHeaders();
        headers.set("Signature", signatureHeader);
        return headers;
    }

    private static void addPostHeaders(HttpHeaders headers) {
        headers.set(HttpHeaders.DATE, SignatureFixtures.DATE);
        headers.set("Digest", "SHA-256=" + SignatureFixtures.BODY_DIGEST);
    }

    private static byte[] body() {
        return SignatureFixtures.BODY.getBytes(StandardCharsets.UTF_8);
    }
}
