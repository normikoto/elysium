package dev.biribiri.elysium.activitypub.http;

import dev.biribiri.elysium.actor.InstanceProperties;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
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

    @Autowired
    FederationHttpClient(BuildProperties build, InstanceProperties instance) {
        this(new PublicAddressDnsResolver(SystemDefaultDnsResolver.INSTANCE, PublicAddressDnsResolver.PUBLIC_ADDRESSES),
                HTTPS_ONLY,
                DEADLINE,
                "Elysium/" + build.getVersion() + " (+" + instance.resolve("/") + ")");
    }

    FederationHttpClient(DnsResolver resolver, Set<String> allowedSchemes, Duration deadline, String userAgent) {
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
                        .setMaxConnPerRoute(4)
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
     * @return the response, whatever its status
     * @throws BlockedTargetException if the URL isn't one this client will fetch
     * @throws ResponseTooLargeException if the response is over the limit
     * @throws IOException if the request fails or takes too long
     */
    public Response get(URI uri, String accept) throws IOException {
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!allowedSchemes.contains(scheme)) {
            throw new BlockedTargetException("Refusing to fetch a URL with this scheme: " + scheme);
        }

        var request = new HttpGet(uri);
        request.setHeader(HttpHeaders.ACCEPT, accept);
        var cancellation = CompletableFuture.runAsync(
                request::cancel,
                CompletableFuture.delayedExecutor(deadline.toMillis(), TimeUnit.MILLISECONDS));
        try {
            return client.execute(request, FederationHttpClient::read);
        } finally {
            cancellation.cancel(false);
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    private static Response read(ClassicHttpResponse response) throws IOException {
        var entity = response.getEntity();
        var contentType = response.getFirstHeader(HttpHeaders.CONTENT_TYPE);
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
        return new Response(response.getCode(), contentType == null ? null : contentType.getValue(), body);
    }

    /**
     * @param contentType the {@code Content-Type} header, or null if it was missing
     */
    public record Response(int status, String contentType, byte[] body) {
    }
}
