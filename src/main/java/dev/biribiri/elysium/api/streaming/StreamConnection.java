package dev.biribiri.elysium.api.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One client's connection, over a WebSocket or server-sent events, and the streams it's subscribed to.
 * <p>
 * Everything it sends goes through one queue and one thread, so that writes never overlap, which neither transport allows,
 * and so that a client that reads slowly can't hold up the listener that's telling every connection about a status. One
 * that falls too far behind is closed, as it would otherwise keep a growing backlog in memory.
 */
public final class StreamConnection {

    private static final Logger log = LoggerFactory.getLogger(StreamConnection.class);

    /** How many messages can wait to be written before the client is taken to be gone. */
    private static final int BACKLOG = 256;

    /**
     * How a transport writes what the connection sends.
     */
    public interface Transport {
        void event(String event, List<String> stream, String payload) throws IOException;

        void error(String message, int status) throws IOException;

        /** Something to write when nothing else is, so that proxies and clients don't take the connection to be idle. */
        void heartbeat() throws IOException;

        /** Ends the connection from this side, which is safe to call more than once. */
        void close();
    }

    private sealed interface Message {
        record Event(String event, List<String> stream, String payload) implements Message {
        }

        record Error(String message, int status) implements Message {
        }

        record Heartbeat() implements Message {
        }

        record Close() implements Message {
        }
    }

    private final Optional<UUID> viewer;
    private final Set<String> scopes;
    private final Optional<String> token;
    private final Transport transport;
    private final Set<StreamKey> subscriptions = ConcurrentHashMap.newKeySet();
    private final BlockingQueue<Message> queue = new ArrayBlockingQueue<>(BACKLOG);
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Runnable onClose = () -> {
    };

    StreamConnection(Optional<UUID> viewer, Set<String> scopes, Optional<String> token, Transport transport) {
        this.viewer = viewer;
        this.scopes = Set.copyOf(scopes);
        this.token = token;
        this.transport = transport;
        Thread.ofVirtual().name("stream-writer").start(this::write);
    }

    public Optional<UUID> viewer() {
        return viewer;
    }

    public Set<String> scopes() {
        return scopes;
    }

    /** The token it was opened with, or empty if it has none, which the heartbeat checks is still valid. */
    Optional<String> token() {
        return token;
    }

    public boolean isAnonymous() {
        return token.isEmpty();
    }

    public Set<StreamKey> subscriptions() {
        return Set.copyOf(subscriptions);
    }

    boolean add(StreamKey key) {
        return subscriptions.add(key);
    }

    int subscriptionCount() {
        return subscriptions.size();
    }

    boolean remove(StreamKey key) {
        return subscriptions.remove(key);
    }

    void onClose(Runnable onClose) {
        this.onClose = onClose;
    }

    public void send(StreamKey key, String event, String payload) {
        offer(new Message.Event(event, key.wireName(), payload));
    }

    public void error(String message, int status) {
        offer(new Message.Error(message, status));
    }

    public void heartbeat() {
        offer(new Message.Heartbeat());
    }

    /**
     * Closes the connection once what's already queued has been written, such as an error saying why.
     */
    public void close() {
        if (!queue.offer(new Message.Close())) {
            closeNow();
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    private void offer(Message message) {
        if (closed.get()) return;
        if (!queue.offer(message)) {
            log.debug("Closing a stream that fell {} messages behind", BACKLOG);
            closeNow();
        }
    }

    /**
     * Closes the connection without writing anything else, as when the client has already gone.
     */
    public void closeNow() {
        if (closed.compareAndSet(false, true)) {
            queue.clear();
            // Wakes the writer, which stops once it sees the connection is closed.
            queue.offer(new Message.Close());
            transport.close();
            onClose.run();
        }
    }

    private void write() {
        try {
            while (!closed.get()) {
                switch (queue.take()) {
                    case Message.Event(var event, var stream, var payload) -> transport.event(event, stream, payload);
                    case Message.Error(var message, var status) -> transport.error(message, status);
                    case Message.Heartbeat() -> transport.heartbeat();
                    case Message.Close() -> closeNow();
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Closing a stream that couldn't be written to", e);
            closeNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeNow();
        }
    }
}
