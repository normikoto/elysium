package dev.biribiri.elysium.activitypub.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.hc.core5.net.URIAuthority;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs against a real server on the loopback address. Each test makes its own client, because a reused connection doesn't
 * go through the resolver again.
 */
class FederationHttpClientTests {

    private final DomainBlocks blocks = mock();

    private static final Set<String> HTTP = Set.of("http");
    private static final Duration LONG_DEADLINE = Duration.ofSeconds(30);
    private static final int ONE_MEBIBYTE = 1024 * 1024;
    private static final HttpHeaders NO_HEADERS = new HttpHeaders();

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().getPath());
            respond(exchange, 200, "{}");
        });
        server.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        for (var closeable : closeables) {
            closeable.close();
        }
        server.stop(0);
    }

    @Test
    void reachesAnAllowedServer() throws Exception {
        var response = allowingLoopback().get(url("/doc"), "application/activity+json", NO_HEADERS);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.contentType()).isEqualTo("application/activity+json");
        assertThat(response.body()).asString().isEqualTo("{}");
    }

    /**
     * {@link #reachesAnAllowedServer()} shows that this server can be reached when the address is allowed, so it's the
     * address check that stops these.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "localhost", "127.0.0.1", "127.1", "2130706433", "0x7f.0.0.1", "[::1]", "[::ffff:127.0.0.1]"
    })
    void neverConnectsToLoopbackHoweverItIsWritten(String host) {
        var client = publicAddressesOnly();
        var target = URI.create("http://" + host + ":" + port() + "/doc");

        assertThatThrownBy(() -> client.get(target, "*/*", NO_HEADERS)).isInstanceOf(IOException.class);

        assertThat(requests).isEmpty();
    }

    @Test
    void reportsABlockedAddressAsBlocked() {
        assertThatThrownBy(() -> publicAddressesOnly().get(url("/doc"), "*/*", NO_HEADERS))
                .isInstanceOf(BlockedTargetException.class);

        assertThat(requests).isEmpty();
    }

    @Test
    void refusesABlockedServerWithoutConnecting() {
        when(blocks.isBlocked("blocked.test")).thenReturn(true);
        // This resolver fails the test if anything tries to look the host up.
        var client = client(new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) {
                throw new AssertionError("Looked up " + host);
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                throw new AssertionError("Looked up " + host);
            }
        }, HTTP, LONG_DEADLINE);
        var url = URI.create("http://blocked.test:8443/");
        var headers = new org.springframework.http.HttpHeaders();

        assertThatThrownBy(() -> client.get(url, "*/*", headers)).isInstanceOf(BlockedTargetException.class);
        assertThatThrownBy(() -> client.post(url, "text/plain", new byte[0], headers))
                .isInstanceOf(BlockedTargetException.class);
    }

    @Test
    void refusesAHostItCannotReadBecauseItCouldNotCheckIt() {
        var client = client(allAddresses(), HTTP, LONG_DEADLINE);
        var url = URI.create("http://foo_bar.blocked.test/");

        assertThatThrownBy(() -> client.get(url, "*/*", new org.springframework.http.HttpHeaders()))
                .isInstanceOf(BlockedTargetException.class);
    }

    @Test
    void blocksAHostnameThatResolvesToLoopback() {
        var resolver = new PublicAddressDnsResolver(
                resolvingTo(InetAddress.getLoopbackAddress()), PublicAddressDnsResolver.PUBLIC_ADDRESSES);
        var client = client(resolver, HTTP, LONG_DEADLINE);
        var target = URI.create("http://public.example:" + port() + "/doc");

        assertThatThrownBy(() -> client.get(target, "*/*", NO_HEADERS)).isInstanceOf(BlockedTargetException.class);

        assertThat(requests).isEmpty();
    }

    @Test
    void refusesOtherSchemes() {
        var client = client(allAddresses(), Set.of("https"), LONG_DEADLINE);

        assertThatThrownBy(() -> client.get(url("/doc"), "*/*", NO_HEADERS)).isInstanceOf(BlockedTargetException.class);

        assertThat(requests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 307})
    void doesNotFollowRedirects(int status) throws Exception {
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().set("Location", "/doc");
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });

        assertThat(allowingLoopback().get(url("/moved"), "*/*", NO_HEADERS).status()).isEqualTo(status);

        assertThat(requests).isEmpty();
    }

    // Downloads

    @Test
    void aDownloadFollowsRedirectsAndStreamsTheBody() throws Exception {
        redirect("/first", "/second");
        redirect("/second", url("/file").toString());
        server.createContext("/file", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            // Chunked, with no length.
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write(new byte[5000]);
            }
        });

        var download = allowingLoopback().download(url("/first"), 10_000, LONG_DEADLINE, 3,
                (contentType, body) -> contentType + " " + body.readAllBytes().length);

        assertThat(download.status()).isEqualTo(200);
        assertThat(download.body()).isEqualTo("image/png 5000");
    }

    @Test
    void aDownloadStopsAfterTooManyRedirects() {
        redirect("/loop", "/loop");

        assertThatThrownBy(() -> allowingLoopback().download(url("/loop"), 10, LONG_DEADLINE, 3, (type, body) -> body))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("redirected more than 3 times");
    }

    @Test
    void aDownloadChecksEachRedirect() {
        when(blocks.isBlocked("blocked.test")).thenReturn(true);
        redirect("/to-blocked", "http://blocked.test:" + port() + "/file");
        redirect("/to-private", "http://10.11.12.13:" + port() + "/file");
        var loopbackOnly = client(new PublicAddressDnsResolver(
                SystemDefaultDnsResolver.INSTANCE, InetAddress::isLoopbackAddress), HTTP, LONG_DEADLINE);

        assertThatThrownBy(() -> allowingLoopback().download(url("/to-blocked"), 10, LONG_DEADLINE, 3, (type, body) -> body))
                .isInstanceOf(BlockedTargetException.class);
        assertThatThrownBy(() -> loopbackOnly.download(url("/to-private"), 10, LONG_DEADLINE, 3, (type, body) -> body))
                .isInstanceOf(BlockedTargetException.class);
        assertThatThrownBy(() -> allowingLoopback().download(URI.create("ftp://example.test/a"), 10, LONG_DEADLINE, 3,
                (type, body) -> body)).isInstanceOf(BlockedTargetException.class);
    }

    @Test
    void aDownloadStopsAtItsLimitWithOrWithoutALength() {
        server.createContext("/sized", exchange -> {
            exchange.sendResponseHeaders(200, 2048);
            try (var body = exchange.getResponseBody()) {
                body.write(new byte[2048]);
            }
        });
        server.createContext("/chunked", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write(new byte[2048]);
            } catch (IOException ignored) {
                // The client stops reading.
            }
        });

        assertThatThrownBy(() -> allowingLoopback().download(url("/sized"), 1024, LONG_DEADLINE, 0,
                (type, body) -> body.readAllBytes())).isInstanceOf(ResponseTooLargeException.class);
        assertThatThrownBy(() -> allowingLoopback().download(url("/chunked"), 1024, LONG_DEADLINE, 0,
                (type, body) -> body.readAllBytes())).isInstanceOf(ResponseTooLargeException.class);
    }

    @Test
    void aDownloadGivesUpAtItsDeadline() {
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                for (var i = 0; i < 50; i++) {
                    body.write(1);
                    body.flush();
                    Thread.sleep(100);
                }
            } catch (IOException | InterruptedException ignored) {
                // The client hung up.
            }
        });

        assertThatThrownBy(() -> allowingLoopback().download(url("/slow"), 1024, Duration.ofMillis(500), 0,
                (type, body) -> body.readAllBytes())).isInstanceOf(IOException.class);
    }

    @Test
    void aDownloadGivesTheStatusOfAFailure() throws Exception {
        server.createContext("/gone", exchange -> respond(exchange, 410, "{}"));

        var download = allowingLoopback().download(url("/gone"), 1024, LONG_DEADLINE, 0, (type, body) -> "read");

        assertThat(download.status()).isEqualTo(410);
        assertThat(download.body()).isNull();
    }

    private void redirect(String from, String to) {
        server.createContext(from, exchange -> {
            exchange.getResponseHeaders().set("Location", to);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
    }

    @Test
    void sendsTheAcceptHeaderUserAgentAndExtraHeaders() throws Exception {
        var received = new ArrayList<String>();
        server.createContext("/headers", exchange -> {
            received.add(exchange.getRequestHeaders().getFirst("Accept"));
            received.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            received.add(exchange.getRequestHeaders().getFirst("X-Extra"));
            respond(exchange, 200, "{}");
        });
        var extra = new HttpHeaders();
        extra.set("X-Extra", "value");

        allowingLoopback().get(url("/headers"), "application/activity+json", extra);

        assertThat(received).containsExactly("application/activity+json", "Elysium/test", "value");
    }

    /**
     * Signatures cover the target and the {@code Host} header, so neither can differ from what's in the URL.
     */
    @Test
    void sendsThePathAndQueryAsTheyAreWritten() throws Exception {
        var received = new ArrayList<String>();
        server.createContext("/a", exchange -> {
            received.add(exchange.getRequestURI().getRawPath() + "?" + exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, "{}");
        });

        allowingLoopback().get(url("/a%7Eb/%2F/./c?x=%7E&y=1"), "application/activity+json", NO_HEADERS);

        assertThat(received).containsExactly("/a%7Eb/%2F/./c?x=%7E&y=1");
    }

    @ParameterizedTest
    @CsvSource({"https,443", "http,80"})
    void leavesADefaultPortOutOfTheHostHeader(String scheme, int port) {
        var authority = FederationHttpClient.canonicalAuthority(scheme, new URIAuthority("remote.example", port));

        assertThat(authority).hasToString("remote.example");
    }

    @Test
    void acceptsABodyAtTheLimit() throws Exception {
        server.createContext("/big", exchange -> respond(exchange, 200, "a".repeat(ONE_MEBIBYTE)));

        assertThat(allowingLoopback().get(url("/big"), "*/*", NO_HEADERS).body()).hasSize(ONE_MEBIBYTE);
    }

    @Test
    void refusesALargeBodyThatDeclaresItsLength() {
        server.createContext("/big", exchange -> respond(exchange, 200, "a".repeat(ONE_MEBIBYTE + 1)));

        assertThatThrownBy(() -> allowingLoopback().get(url("/big"), "*/*", NO_HEADERS))
                .isInstanceOf(ResponseTooLargeException.class);
    }

    @Test
    void refusesALargeBodyThatDoesNotDeclareItsLength() {
        server.createContext("/big", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write(new byte[ONE_MEBIBYTE + 1]);
            }
        });

        assertThatThrownBy(() -> allowingLoopback().get(url("/big"), "*/*", NO_HEADERS))
                .isInstanceOf(ResponseTooLargeException.class);
    }

    @Test
    void givesUpOnAResponseThatKeepsTricklingIn() {
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                for (var i = 0; i < 200; i++) {
                    body.write('a');
                    body.flush();
                    Thread.sleep(50);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        var client = client(allAddresses(), HTTP, Duration.ofMillis(300));
        var start = System.nanoTime();

        assertThatThrownBy(() -> client.get(url("/slow"), "*/*", NO_HEADERS)).isInstanceOf(IOException.class);

        // Each byte arrives well within the read timeout, so only the deadline can have ended this.
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void reportsAServerThatIsDownAsAnIoFailure() throws Exception {
        var target = url("/doc");
        server.stop(0);

        assertThatThrownBy(() -> allowingLoopback().get(target, "*/*", NO_HEADERS))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(BlockedTargetException.class);
    }

    private FederationHttpClient allowingLoopback() {
        return client(allAddresses(), HTTP, LONG_DEADLINE);
    }

    private FederationHttpClient publicAddressesOnly() {
        return client(new PublicAddressDnsResolver(
                SystemDefaultDnsResolver.INSTANCE, PublicAddressDnsResolver.PUBLIC_ADDRESSES), HTTP, LONG_DEADLINE);
    }

    private FederationHttpClient client(DnsResolver resolver, Set<String> schemes, Duration deadline) {
        var client = new FederationHttpClient(resolver, schemes, deadline, "Elysium/test", blocks);
        closeables.add(client);
        return client;
    }

    private static DnsResolver allAddresses() {
        return new PublicAddressDnsResolver(SystemDefaultDnsResolver.INSTANCE, InetAddressFilter.all());
    }

    private static DnsResolver resolvingTo(InetAddress address) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) {
                return new InetAddress[]{address};
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }

    private URI url(String path) {
        return URI.create("http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + port() + path);
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/activity+json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }
}
