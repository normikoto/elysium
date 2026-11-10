package dev.biribiri.elysium.activitypub.signature;

import com.sun.net.httpserver.HttpServer;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.http.LoopbackClients;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.signing.ActorSigningKeys;
import dev.biribiri.elysium.actor.signing.SigningKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sends a signed fetch with the real client, and checks the request that a server actually received. Signer tests can't
 * catch the client changing something that was signed, such as the {@code Host} header or how the path is encoded.
 */
class SignedRequestOnTheWireTests {

    private static final SigningKey KEY =
            new SigningKey("https://example.test/actor#main-key", SignatureFixtures.privateKey());

    private final AtomicReference<HttpRequestData> received = new AtomicReference<>();
    private HttpServer server;
    private FederationHttpClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            var headers = new HttpHeaders();
            exchange.getRequestHeaders().forEach((name, values) -> values.forEach(value -> headers.add(name, value)));
            var uri = exchange.getRequestURI();
            var target = uri.getRawQuery() == null ? uri.getRawPath() : uri.getRawPath() + "?" + uri.getRawQuery();
            received.set(new HttpRequestData(exchange.getRequestMethod(), target, headers, exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        client = LoopbackClients.create();
    }

    @AfterEach
    void stop() throws IOException {
        client.close();
        server.stop(0);
    }

    @ParameterizedTest
    @EnumSource
    void aServerCanCheckAPathWithOtherCharacters(Format format) throws Exception {
        assertCanBeChecked(format, "/users/ü?name=ü", "/users/%C3%BC?name=%C3%BC");
    }

    @ParameterizedTest
    @MethodSource("pathsInEachFormat")
    void aServerCanCheckWhatItReceives(Format format, String pathAndQuery) throws Exception {
        assertCanBeChecked(format, pathAndQuery, pathAndQuery);
    }

    @ParameterizedTest
    @EnumSource
    void aServerCanCheckASentActivity(Format format) throws Exception {
        var sender = new SignedSender(client, List.of(format.signer()));
        var authority = "remote.example:" + server.getAddress().getPort();
        var body = "{\"text\":\"ü\"}".getBytes(StandardCharsets.UTF_8);

        sender.post(URI.create("http://Remote.Example:" + server.getAddress().getPort() + "/inbox"), body, KEY);

        var request = received.get();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.body()).isEqualTo(body);
        assertThat(request.headers().getFirst("Content-Type")).isEqualTo("application/activity+json");
        var claim = format.scheme(new InstanceProperties(URI.create("http://" + authority))).parse(request);
        assertThat(claim.keyId()).isEqualTo(KEY.keyId());
        assertThat(claim.algorithm().verify(SignatureFixtures.publicKey(), claim.signatureBase(), claim.signature()))
                .isTrue();
    }

    static Stream<Arguments> pathsInEachFormat() {
        return Stream.of(Format.values()).flatMap(format -> Stream.of(
                        "/users/bob", "/users/bob/main-key", "/a%7Eb/%2F/c", "/users/bob?page=1&x=%7E")
                .map(path -> Arguments.of(format, path)));
    }

    private void assertCanBeChecked(Format format, String pathAndQuery, String expectedTarget) throws Exception {
        var keys = mock(ActorSigningKeys.class);
        when(keys.forInstance()).thenReturn(KEY);
        var fetcher = new SignedFetcher(client, List.of(format.signer()), keys);
        var authority = "remote.example:" + server.getAddress().getPort();

        fetcher.get(URI.create("http://Remote.Example:" + server.getAddress().getPort() + pathAndQuery),
                "application/activity+json");

        var request = received.get();
        assertThat(request.rawTarget()).isEqualTo(expectedTarget);
        // This server's own scheme signs with its own name rather than reading this header, but other servers read it.
        assertThat(request.headers().getFirst("Host")).isEqualTo(authority);
        var claim = format.scheme(new InstanceProperties(URI.create("http://" + authority))).parse(request);
        assertThat(claim.keyId()).isEqualTo(KEY.keyId());
        assertThat(claim.algorithm().verify(SignatureFixtures.publicKey(), claim.signatureBase(), claim.signature()))
                .isTrue();
    }

    enum Format {
        CAVAGE {
            @Override
            RequestSigner signer() {
                return new CavageRequestSigner();
            }

            @Override
            SignatureScheme scheme(InstanceProperties instance) {
                return new CavageSignatureScheme(instance);
            }
        },
        RFC_9421 {
            @Override
            RequestSigner signer() {
                return new Rfc9421RequestSigner();
            }

            @Override
            SignatureScheme scheme(InstanceProperties instance) {
                return new Rfc9421SignatureScheme(instance);
            }
        };

        abstract RequestSigner signer();

        abstract SignatureScheme scheme(InstanceProperties instance);
    }
}
