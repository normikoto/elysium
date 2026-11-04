package dev.biribiri.elysium.activitypub.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.http.client.InetAddressFilter;

import java.io.IOException;
import java.net.Inet6Address;
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

/**
 * Runs against a real server on the loopback address. Each test makes its own client, because a reused connection doesn't
 * go through the resolver again.
 */
class FederationHttpClientTests {

    private static final Set<String> HTTP = Set.of("http");
    private static final Duration LONG_DEADLINE = Duration.ofSeconds(30);
    private static final int ONE_MEBIBYTE = 1024 * 1024;

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
        var response = allowingLoopback().get(url("/doc"), "application/activity+json");

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

        assertThatThrownBy(() -> client.get(target, "*/*")).isInstanceOf(IOException.class);

        assertThat(requests).isEmpty();
    }

    @Test
    void reportsABlockedAddressAsBlocked() {
        assertThatThrownBy(() -> publicAddressesOnly().get(url("/doc"), "*/*"))
                .isInstanceOf(BlockedTargetException.class);

        assertThat(requests).isEmpty();
    }

    @Test
    void blocksAHostnameThatResolvesToLoopback() {
        var resolver = new PublicAddressDnsResolver(
                resolvingTo(InetAddress.getLoopbackAddress()), PublicAddressDnsResolver.PUBLIC_ADDRESSES);
        var client = client(resolver, HTTP, LONG_DEADLINE);
        var target = URI.create("http://public.example:" + port() + "/doc");

        assertThatThrownBy(() -> client.get(target, "*/*")).isInstanceOf(BlockedTargetException.class);

        assertThat(requests).isEmpty();
    }

    @Test
    void blocksAHostnameWhoseDnsAnswerMapsToLoopback() throws Exception {
        var bytes = new byte[16];
        bytes[10] = (byte) 0xff;
        bytes[11] = (byte) 0xff;
        System.arraycopy(InetAddress.getLoopbackAddress().getAddress(), 0, bytes, 12, 4);
        var resolver = new PublicAddressDnsResolver(
                resolvingTo(Inet6Address.getByAddress(null, bytes, -1)), PublicAddressDnsResolver.PUBLIC_ADDRESSES);
        var client = client(resolver, HTTP, LONG_DEADLINE);
        var target = URI.create("http://public.example:" + port() + "/doc");

        assertThatThrownBy(() -> client.get(target, "*/*")).isInstanceOf(BlockedTargetException.class);

        assertThat(requests).isEmpty();
    }

    @Test
    void refusesOtherSchemes() {
        var client = client(allAddresses(), Set.of("https"), LONG_DEADLINE);

        assertThatThrownBy(() -> client.get(url("/doc"), "*/*")).isInstanceOf(BlockedTargetException.class);

        assertThat(requests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void doesNotFollowRedirects(int status) throws Exception {
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().set("Location", "/doc");
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });

        assertThat(allowingLoopback().get(url("/moved"), "*/*").status()).isEqualTo(status);

        assertThat(requests).isEmpty();
    }

    @Test
    void sendsTheAcceptHeaderAndUserAgent() throws Exception {
        var received = new ArrayList<String>();
        server.createContext("/headers", exchange -> {
            received.add(exchange.getRequestHeaders().getFirst("Accept"));
            received.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            respond(exchange, 200, "{}");
        });

        allowingLoopback().get(url("/headers"), "application/activity+json");

        assertThat(received).containsExactly("application/activity+json", "Elysium/test");
    }

    @Test
    void acceptsABodyAtTheLimit() throws Exception {
        server.createContext("/big", exchange -> respond(exchange, 200, "a".repeat(ONE_MEBIBYTE)));

        assertThat(allowingLoopback().get(url("/big"), "*/*").body()).hasSize(ONE_MEBIBYTE);
    }

    @Test
    void refusesALargeBodyThatDeclaresItsLength() {
        server.createContext("/big", exchange -> respond(exchange, 200, "a".repeat(ONE_MEBIBYTE + 1)));

        assertThatThrownBy(() -> allowingLoopback().get(url("/big"), "*/*"))
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

        assertThatThrownBy(() -> allowingLoopback().get(url("/big"), "*/*"))
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

        assertThatThrownBy(() -> client.get(url("/slow"), "*/*")).isInstanceOf(IOException.class);

        // Each byte arrives well within the read timeout, so only the deadline can have ended this.
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void reportsAServerThatIsDownAsAnIoFailure() throws Exception {
        var target = url("/doc");
        server.stop(0);

        assertThatThrownBy(() -> allowingLoopback().get(target, "*/*"))
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
        var client = new FederationHttpClient(resolver, schemes, deadline, "Elysium/test");
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
