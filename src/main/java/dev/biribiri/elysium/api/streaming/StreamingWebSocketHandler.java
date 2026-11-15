package dev.biribiri.elysium.api.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mastodon's WebSocket streaming: one connection, subscribed to streams by the query it opens with and by
 * {@code subscribe} and {@code unsubscribe} messages. A message that can't be followed gets an error message and leaves
 * the connection open, as on Mastodon, except for the stream it opens with, which Mastodon also closes it for.
 */
@Component
class StreamingWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamingWebSocketHandler.class);

    private static final String CONNECTION = StreamConnection.class.getName();

    /** Clients only send short subscribe and unsubscribe messages. */
    private static final int MAX_MESSAGE_SIZE = 4096;

    private final StreamRegistry registry;
    private final JsonMapper json;

    StreamingWebSocketHandler(StreamRegistry registry, JsonMapper json) {
        this.registry = registry;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        session.setTextMessageSizeLimit(MAX_MESSAGE_SIZE);
        session.setBinaryMessageSizeLimit(MAX_MESSAGE_SIZE);
        StreamConnection connection;
        try {
            connection = registry.open((Authentication) session.getPrincipal(), new Transport(session, json));
        } catch (StreamRegistry.TooManyConnectionsException e) {
            session.close(CloseStatus.SERVICE_OVERLOAD);
            return;
        }
        session.getAttributes().put(CONNECTION, connection);

        var query = query(session);
        var stream = query.get("stream");
        if (stream != null) {
            if (!subscribe(connection, stream, query.get("tag"))) {
                connection.close();
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (!(session.getAttributes().get(CONNECTION) instanceof StreamConnection connection)) return;
        JsonNode body;
        try {
            body = json.readTree(message.getPayload());
        } catch (JacksonException e) {
            connection.error("Could not parse message", 400);
            return;
        }
        if (body == null || !body.isObject()) {
            connection.error("Could not parse message", 400);
            return;
        }
        var type = text(body, "type");
        var stream = text(body, "stream");
        var tag = text(body, "tag");
        switch (type == null ? "" : type) {
            case "subscribe" -> subscribe(connection, stream, tag);
            case "unsubscribe" -> {
                try {
                    registry.unsubscribe(connection, StreamKey.parse(stream, tag));
                } catch (StreamKey.InvalidStreamException e) {
                    connection.error(e.getMessage(), 400);
                }
            }
            default -> connection.error("Unknown message type", 400);
        }
    }

    /**
     * @return whether it subscribed
     */
    private boolean subscribe(StreamConnection connection, String stream, String tag) {
        try {
            var refusal = registry.subscribe(connection, StreamKey.parse(stream, tag));
            refusal.ifPresent(reason -> connection.error(reason.message(), reason.status()));
            return refusal.isEmpty();
        } catch (StreamKey.InvalidStreamException e) {
            connection.error(e.getMessage(), 400);
            return false;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (session.getAttributes().get(CONNECTION) instanceof StreamConnection connection) {
            connection.closeNow();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Streaming WebSocket failed", exception);
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
    }

    private static String text(JsonNode body, String field) {
        var value = body.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    private static Map<String, String> query(WebSocketSession session) {
        var found = new LinkedHashMap<String, String>();
        if (session.getUri() == null) return found;
        UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams().forEach((name, values) -> {
            if (!values.isEmpty() && values.getFirst() != null) {
                found.put(decode(name), decode(values.getFirst()));
            }
        });
        return found;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /**
     * Writes Mastodon's messages, whose payload is itself JSON, sent as a string.
     */
    private record Transport(WebSocketSession session, JsonMapper json) implements StreamConnection.Transport {

        @Override
        public void event(String event, List<String> stream, String payload) throws IOException {
            var message = new LinkedHashMap<String, Object>();
            message.put("stream", stream);
            message.put("event", event);
            if (payload != null) {
                message.put("payload", payload);
            }
            session.sendMessage(new TextMessage(json.writeValueAsString(message)));
        }

        @Override
        public void error(String message, int status) throws IOException {
            session.sendMessage(new TextMessage(json.writeValueAsString(Map.of("error", message, "status", status))));
        }

        @Override
        public void heartbeat() throws IOException {
            session.sendMessage(new PingMessage());
        }

        @Override
        public void close() {
            try {
                session.close();
            } catch (IOException e) {
                log.debug("Couldn't close a streaming WebSocket", e);
            }
        }
    }
}
