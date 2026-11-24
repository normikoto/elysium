package dev.biribiri.elysium.push.internal;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.http.BlockedTargetException;
import dev.biribiri.elysium.http.PublicAddressDnsResolver;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.utils.DateUtils;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sends messages to push services. Users choose the endpoints, so this won't reach private addresses, and it limits how
 * long a request can take and how much of a response it reads, as {@code FederationHttpClient} does. It's built from
 * the plain client builder for the same reason: Spring Boot's would go through the JVM's proxy, and the resolver would
 * then check the proxy rather than the endpoint.
 */
@Component
public class PushHttpClient implements AutoCloseable {

    private static final Set<String> HTTPS_ONLY = Set.of("https");
    private static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(5);
    private static final Timeout READ_TIMEOUT = Timeout.ofSeconds(10);
    private static final Duration DEADLINE = Duration.ofSeconds(20);

    /**
     * Push services answer with a status and little else.
     */
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;

    private final CloseableHttpClient client;
    private final Set<String> allowedSchemes;

    @Autowired
    PushHttpClient(BuildProperties build, InstanceProperties instance) {
        this(new PublicAddressDnsResolver(SystemDefaultDnsResolver.INSTANCE, PublicAddressDnsResolver.PUBLIC_ADDRESSES),
                HTTPS_ONLY,
                "Elysium/" + build.getVersion() + " (+" + instance.resolve("/") + ")");
    }

    public PushHttpClient(DnsResolver resolver, Set<String> allowedSchemes, String userAgent) {
        this.allowedSchemes = allowedSchemes;
        this.client = HttpClients.custom()
                .setUserAgent(userAgent)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(CONNECT_TIMEOUT)
                        .setResponseTimeout(READ_TIMEOUT)
                        .build())
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(resolver)
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(CONNECT_TIMEOUT)
                                .setSocketTimeout(READ_TIMEOUT)
                                .setTimeToLive(TimeValue.ofMinutes(1))
                                .build())
                        // Most subscriptions go to a handful of services, such as Firebase's and Mozilla's.
                        .setMaxConnTotal(32)
                        .setMaxConnPerRoute(8)
                        .build())
                .disableRedirectHandling()
                .disableContentCompression()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableAutomaticRetries()
                .build();
    }

    /**
     * Whether this client would send to the URL, so that a subscription it never could is refused when it's made.
     */
    public boolean accepts(URI endpoint) {
        var scheme = endpoint.getScheme() == null ? "" : endpoint.getScheme().toLowerCase(Locale.ROOT);
        return allowedSchemes.contains(scheme) && endpoint.getHost() != null;
    }

    /**
     * Blocks until it's finished, so this mustn't be called inside a database transaction.
     *
     * @throws BlockedTargetException if the endpoint isn't one this client will send to
     * @throws IOException            if the request fails, takes too long, or the response is too large
     */
    public Response post(URI endpoint, byte[] body, Map<String, String> headers) throws IOException {
        if (!accepts(endpoint)) {
            throw new BlockedTargetException("Refusing to send to this endpoint");
        }
        var request = new HttpPost(URI.create(endpoint.toASCIIString()));
        headers.forEach(request::setHeader);
        request.setHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream");
        request.setEntity(new ByteArrayEntity(body, null));
        // The read timeout only limits the gap between packets, so a server could otherwise keep a request open forever.
        var timer = new CompletableFuture<Void>().orTimeout(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        timer.exceptionallyAsync(timedOut -> {
            request.cancel();
            return null;
        });
        try {
            return client.execute(request, PushHttpClient::read);
        } finally {
            timer.complete(null);
        }
    }

    /**
     * Throwing closes the connection rather than reading the rest, which the client would otherwise do with no limit.
     */
    private static Response read(ClassicHttpResponse response) throws IOException {
        var entity = response.getEntity();
        if (entity != null) {
            try (var stream = entity.getContent()) {
                if (stream.readNBytes(MAX_RESPONSE_BYTES + 1).length > MAX_RESPONSE_BYTES) {
                    throw new IOException("Response is over " + MAX_RESPONSE_BYTES + " bytes");
                }
            }
        }
        var retryAfter = response.getFirstHeader(HttpHeaders.RETRY_AFTER);
        return new Response(response.getCode(), retryAfter == null ? null : retryAfter.getValue());
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    /**
     * @param retryAfter the {@code Retry-After} header, or null if it was missing
     */
    public record Response(int status, String retryAfter) {

        /**
         * @return how long the push service asks this server to wait, or zero if it doesn't say
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
