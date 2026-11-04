package dev.biribiri.elysium.activitypub.signature;

import com.sun.net.httpserver.HttpServer;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.http.LoopbackClients;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.signing.ActorSigningKeys;
import dev.biribiri.elysium.actor.signing.SigningKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void aServerCanCheckAPathWithOtherCharacters() throws Exception {
        assertCanBeChecked("/users/ü?name=ü", "/users/%C3%BC?name=%C3%BC");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/users/bob", "/users/bob/main-key", "/a%7Eb/%2F/c", "/users/bob?page=1&x=%7E"})
    void aServerCanCheckWhatItReceives(String pathAndQuery) throws Exception {
        assertCanBeChecked(pathAndQuery, pathAndQuery);
    }

    @Test
    void aServerCanCheckASentActivity() throws Exception {
        var sender = new SignedSender(client, List.of(new CavageRequestSigner()));
        var authority = "remote.example:" + server.getAddress().getPort();
        var body = "{\"text\":\"ü\"}".getBytes(StandardCharsets.UTF_8);

        sender.post(URI.create("http://Remote.Example:" + server.getAddress().getPort() + "/inbox"), body, KEY);

        var request = received.get();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.body()).isEqualTo(body);
        assertThat(request.headers().getFirst("Content-Type")).isEqualTo("application/activity+json");
        var claim = new CavageSignatureScheme(new InstanceProperties(URI.create("http://" + authority)))
                .parse(request);
        assertThat(claim.keyId()).isEqualTo(KEY.keyId());
        assertThat(claim.algorithm().verify(SignatureFixtures.publicKey(), claim.signatureBase(), claim.signature()))
                .isTrue();
    }

    private void assertCanBeChecked(String pathAndQuery, String expectedTarget) throws Exception {
        var keys = mock(ActorSigningKeys.class);
        when(keys.forInstance()).thenReturn(KEY);
        var fetcher = new SignedFetcher(client, List.of(new CavageRequestSigner()), keys);
        var authority = "remote.example:" + server.getAddress().getPort();

        fetcher.get(URI.create("http://Remote.Example:" + server.getAddress().getPort() + pathAndQuery),
                "application/activity+json");

        var request = received.get();
        assertThat(request.rawTarget()).isEqualTo(expectedTarget);
        // This server's own scheme signs with its own name rather than reading this header, but other servers read it.
        assertThat(request.headers().getFirst("Host")).isEqualTo(authority);
        var claim = new CavageSignatureScheme(new InstanceProperties(URI.create("http://" + authority)))
                .parse(request);
        assertThat(claim.keyId()).isEqualTo(KEY.keyId());
        assertThat(claim.algorithm().verify(SignatureFixtures.publicKey(), claim.signatureBase(), claim.signature()))
                .isTrue();
    }
}
