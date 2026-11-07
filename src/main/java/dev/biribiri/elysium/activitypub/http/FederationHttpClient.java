package dev.biribiri.elysium.activitypub.http;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.utils.DateUtils;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.net.URIAuthority;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Makes requests to other servers, which aren't trusted to behave. It won't reach private addresses, doesn't follow
 * redirects, and limits how long a request can take and how much it can read.
 *
 * <p>This is built from the plain client builder on purpose. Spring Boot's builder also reads the JVM's proxy settings,
 * and going through a proxy would mean that {@link PublicAddressDnsResolver} checks the proxy instead of the target.
 */
@Component
public class FederationHttpClient implements AutoCloseable {

    public static final int MAX_CONNECTIONS_PER_HOST = 4;

    private static final Set<String> HTTPS_ONLY = Set.of("https");
    private static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(5);
    private static final Timeout READ_TIMEOUT = Timeout.ofSeconds(10);

    /**
     * The read timeout only limits the gap between packets, so a server could otherwise keep a request open forever by
     * sending a byte at a time.
     */
    private static final Duration DEADLINE = Duration.ofSeconds(30);
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final CloseableHttpClient client;
    private final Set<String> allowedSchemes;
    private final Duration deadline;
    private final DomainBlocks blocks;

    @Autowired
    FederationHttpClient(
            BuildProperties build, InstanceProperties instance, FederationProperties federation, DomainBlocks blocks) {
        this(new PublicAddressDnsResolver(
                        SystemDefaultDnsResolver.INSTANCE,
                        PublicAddressDnsResolver.publicAddressesAnd(federation.allowedPrivateNetworks())),
                HTTPS_ONLY,
                DEADLINE,
                "Elysium/" + build.getVersion() + " (+" + instance.resolve("/") + ")",
                blocks);
    }

    FederationHttpClient(
            DnsResolver resolver, Set<String> allowedSchemes, Duration deadline, String userAgent, DomainBlocks blocks) {
        this.blocks = blocks;
        this.allowedSchemes = allowedSchemes;
        this.deadline = deadline;
        this.client = HttpClients.custom()
                .setUserAgent(userAgent)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(CONNECT_TIMEOUT)
                        .setResponseTimeout(READ_TIMEOUT)
                        .build())
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(resolver)
                        .setConnectionFactory(ManagedHttpClientConnectionFactory.builder()
                                .http1Config(Http1Config.custom()
                                        .setMaxLineLength(16 * 1024)
                                        .setMaxHeaderCount(200)
                                        .build())
                                .build())
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(CONNECT_TIMEOUT)
                                .setSocketTimeout(READ_TIMEOUT)
                                .setTimeToLive(TimeValue.ofMinutes(1))
                                .build())
                        .setMaxConnTotal(64)
                        .setMaxConnPerRoute(MAX_CONNECTIONS_PER_HOST)
                        .build())
                .disableRedirectHandling()
                // Otherwise a small compressed response could expand past the limit on reading it.
                .disableContentCompression()
                .disableCookieManagement()
                .disableAuthCaching()
                // Callers decide when to try again.
                .disableAutomaticRetries()
                .build();
    }

    /**
     * Fetches a URL. Blocks until it's finished, so this mustn't be called inside a database transaction.
     *
     * @param accept the value of the {@code Accept} header
     * @param extra  headers to send as well, such as a signature
     * @return the response, whatever its status
     * @throws BlockedTargetException if the URL isn't one this client will fetch
     * @throws ResponseTooLargeException if the response is over the limit
     * @throws IOException if the request fails or takes too long
     */
    public Response get(URI uri, String accept, org.springframework.http.HttpHeaders extra) throws IOException {
        var request = new HttpGet(checked(uri));
        request.setHeader(HttpHeaders.ACCEPT, accept);
        return execute(request, extra);
    }

    /**
     * Sends a body to a URL. Blocks until it's finished, so this mustn't be called inside a database transaction.
     *
     * @param contentType the value of the {@code Content-Type} header
     * @param extra       headers to send as well, such as a signature
     * @return the response, whatever its status
     * @throws BlockedTargetException if the URL isn't one this client will send to
     * @throws ResponseTooLargeException if the response is over the limit
     * @throws IOException if the request fails or takes too long
     */
    public Response post(URI uri, String contentType, byte[] body, org.springframework.http.HttpHeaders extra)
            throws IOException {
        var request = new HttpPost(checked(uri));
        // Set as a header and not through the entity, which would add a charset that the receiver hasn't been told about.
        request.setHeader(HttpHeaders.CONTENT_TYPE, contentType);
        request.setEntity(new ByteArrayEntity(body, null));
        return execute(request, extra);
    }

    /**
     * @return the URL in ASCII because that's what's signed. The client would otherwise write other characters as single
     * bytes.
     */
    private URI checked(URI uri) throws BlockedTargetException {
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!allowedSchemes.contains(scheme)) {
            throw new BlockedTargetException("Refusing to fetch a URL with this scheme: " + scheme);
        }
        // URI.getHost is null for names like foo_bar.example, which the request would still connect to, unchecked.
        if (uri.getHost() == null) {
            throw new BlockedTargetException("Refusing to contact a URL whose host can't be read: " + uri);
        }
        if (blocks.isBlocked(uri.getHost())) {
            throw new BlockedTargetException("Refusing to contact a blocked server: " + uri.getHost());
        }
        return URI.create(uri.toASCIIString());
    }

    private Response execute(HttpUriRequestBase request, org.springframework.http.HttpHeaders extra) throws IOException {
        request.setAuthority(canonicalAuthority(request.getScheme(), request.getAuthority()));
        extra.forEach((name, values) -> values.forEach(value -> request.addHeader(name, value)));
        // Finishing this removes its timer. Cancelling a task on a delayedExecutor wouldn't, so that would keep the request and
        // its body in memory until the deadline, however quickly the request finished.
        var timer = new CompletableFuture<Void>().orTimeout(deadline.toMillis(), TimeUnit.MILLISECONDS);
        timer.exceptionallyAsync(timedOut -> {
            request.cancel();
            return null;
        });
        try {
            return client.execute(request, FederationHttpClient::read);
        } finally {
            timer.complete(null);
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    /**
     * What the {@code Host} header will say: a lowercase host, without the scheme's default port. The client would
     * otherwise send a URL's host as it's written, but signatures cover this header, so it has to match what's signed.
     *
     * @return the authority to send, or null if the URL doesn't have one, which the client then refuses
     */
    static URIAuthority canonicalAuthority(String scheme, URIAuthority authority) {
        if (authority == null) {
            return null;
        }
        return new URIAuthority(
                authority.getUserInfo(),
                HostHeader.host(authority.getHostName()),
                HostHeader.port(scheme, authority.getPort()));
    }

    private static Response read(ClassicHttpResponse response) throws IOException {
        var entity = response.getEntity();
        var contentType = response.getFirstHeader(HttpHeaders.CONTENT_TYPE);
        var retryAfter = response.getFirstHeader(HttpHeaders.RETRY_AFTER);
        var location = response.getFirstHeader(HttpHeaders.LOCATION);
        var body = new byte[0];
        if (entity != null) {
            if (entity.getContentLength() > MAX_RESPONSE_BYTES) {
                throw new ResponseTooLargeException("Response is " + entity.getContentLength() + " bytes");
            }
            try (var stream = entity.getContent()) {
                body = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (body.length > MAX_RESPONSE_BYTES) {
                throw new ResponseTooLargeException("Response is over " + MAX_RESPONSE_BYTES + " bytes");
            }
        }
        return new Response(
                response.getCode(),
                contentType == null ? null : contentType.getValue(),
                retryAfter == null ? null : retryAfter.getValue(),
                body,
                location == null ? null : location.getValue());
    }

    /**
     * @param contentType the {@code Content-Type} header, or null if it was missing
     * @param retryAfter  the {@code Retry-After} header, or null if it was missing
     * @param location    the {@code Location} header, or null if it was missing. Redirects aren't followed.
     */
    public record Response(int status, String contentType, String retryAfter, byte[] body, String location) {

        public Response(int status, String contentType, String retryAfter, byte[] body) {
            this(status, contentType, retryAfter, body, null);
        }

        /**
         * @return how long the {@code Retry-After} header asks this server to wait, in either of its forms. This is zero if
         * it doesn't say, and can be negative if it gives a time in the past.
         */
        public Duration retryAfterDelay(Instant now) {
            if (retryAfter == null) {
                return Duration.ZERO;
            }
            var value = retryAfter.strip();
            try {
                return Duration.ofSeconds(Long.parseLong(value));
            } catch (NumberFormatException notSeconds) {
                var date = DateUtils.parseStandardDate(value);
                return date == null ? Duration.ZERO : Duration.between(now, date);
            }
        }
    }
}
