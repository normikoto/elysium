package dev.biribiri.elysium.api.streaming;

import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.graph.AccountLists;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The streaming connections open to this process. They're only kept in memory, so a client only hears about what this
 * process handles, which is everything while the server runs as one process.
 */
@Component
public class StreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(StreamRegistry.class);

    /** As often as Mastodon's, which keeps proxies with a one-minute idle timeout from closing quiet connections. */
    private static final Duration HEARTBEAT = Duration.ofSeconds(30);

    /** Mastodon has no limit, but each subscription costs a check for every status, so a connection can't have many. */
    public static final int MAX_SUBSCRIPTIONS = 50;

    private final Set<StreamConnection> connections = ConcurrentHashMap.newKeySet();
    private final AtomicInteger count = new AtomicInteger();
    private final int maxConnections;
    private final OpaqueTokenIntrospector introspector;
    private final AccountLists lists;
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("stream-heartbeat").daemon().factory());

    StreamRegistry(StreamingProperties properties, OpaqueTokenIntrospector introspector, AccountLists lists) {
        this.maxConnections = properties.maxConnections();
        this.introspector = introspector;
        this.lists = lists;
        heartbeats.scheduleWithFixedDelay(this::beat, HEARTBEAT.toMillis(), HEARTBEAT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Thrown when as many connections are open as the server allows.
     */
    public static class TooManyConnectionsException extends RuntimeException {
        TooManyConnectionsException() {
            super("Too many streaming connections are open. Try again later");
        }
    }

    /**
     * @param authentication who opened it, which is anonymous without a token
     * @throws TooManyConnectionsException if no more can be opened
     */
    public StreamConnection open(Authentication authentication, StreamConnection.Transport transport) {
        if (count.incrementAndGet() > maxConnections) {
            count.decrementAndGet();
            throw new TooManyConnectionsException();
        }
        StreamConnection connection;
        if (authentication instanceof BearerTokenAuthentication bearer
                && bearer.getPrincipal() instanceof TokenPrincipal principal) {
            connection = new StreamConnection(principal.actorId(), principal.scopes(),
                    Optional.of(bearer.getToken().getTokenValue()), transport);
        } else {
            connection = new StreamConnection(Optional.empty(), Set.of(), Optional.empty(), transport);
        }
        connection.onClose(() -> {
            if (connections.remove(connection)) {
                count.decrementAndGet();
            }
        });
        connections.add(connection);
        return connection;
    }

    /**
     * Subscribes a connection to a stream, which it can already be subscribed to.
     *
     * @return why the connection's token can't read it, if it can't, or that a list isn't the viewer's
     * @throws StreamKey.InvalidStreamException if the connection has as many subscriptions as it can
     */
    public Optional<StreamKey.Refusal> subscribe(StreamConnection connection, StreamKey key) {
        var refusal = key.refusal(connection.viewer(), connection.scopes(), connection.isAnonymous());
        if (refusal.isPresent()) {
            return refusal;
        }
        if (key.kind() == StreamKey.Kind.LIST && lists.find(connection.viewer().orElseThrow(), key.listId()).isEmpty()) {
            return Optional.of(StreamKey.Refusal.LIST);
        }
        if (!connection.subscriptions().contains(key) && connection.subscriptionCount() >= MAX_SUBSCRIPTIONS) {
            throw new StreamKey.InvalidStreamException("Too many subscriptions");
        }
        connection.add(key);
        return Optional.empty();
    }

    public void unsubscribe(StreamConnection connection, StreamKey key) {
        connection.remove(key);
    }

    /**
     * The open connections that are subscribed to anything.
     */
    public List<StreamConnection> connections() {
        return connections.stream().filter(connection -> connection.subscriptionCount() > 0).toList();
    }

    /**
     * Keeps quiet connections open, and closes those whose token has since been revoked or has expired, or whose account
     * has been suspended or deleted, since none of those would be let in again.
     */
    private void beat() {
        // A client often opens several connections with one token, and each check is a few queries.
        var valid = new HashMap<String, Boolean>();
        for (var connection : List.copyOf(connections)) {
            try {
                if (connection.token().isPresent() && !valid.computeIfAbsent(
                        connection.token().get(), _ -> stillValid(connection))) {
                    connection.closeNow();
                    continue;
                }
                connection.heartbeat();
            } catch (RuntimeException e) {
                log.warn("Couldn't check a streaming connection", e);
            }
        }
    }

    private boolean stillValid(StreamConnection connection) {
        try {
            var principal = introspector.introspect(connection.token().orElseThrow());
            return principal instanceof TokenPrincipal token && token.actorId().equals(connection.viewer());
        } catch (BadOpaqueTokenException e) {
            return false;
        }
    }

    @PreDestroy
    void shutDown() {
        heartbeats.shutdownNow();
        List.copyOf(connections).forEach(StreamConnection::close);
    }
}
