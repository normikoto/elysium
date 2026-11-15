package dev.biribiri.elysium.api.streaming;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.util.List;

/**
 * Serves streaming over a WebSocket at the path Mastodon's clients connect to.
 * <p>
 * Any origin can connect, since the API never reads cookies: a page on another site can only stream what it already has a
 * token for, as with the rest of the API.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
class StreamingWebSocketConfig implements WebSocketConfigurer {

    static final String PATH = "/api/v1/streaming";

    private final StreamingWebSocketHandler handler;

    StreamingWebSocketConfig(StreamingWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, PATH)
                .setHandshakeHandler(new TokenProtocolHandshakeHandler())
                .setAllowedOrigins("*");
    }

    /**
     * Browsers can't set headers on a WebSocket, so Mastodon's web client sends its token as the
     * {@code Sec-WebSocket-Protocol}. A browser drops the connection unless the server names one of the protocols it
     * offered, so the token is named back.
     */
    private static final class TokenProtocolHandshakeHandler extends DefaultHandshakeHandler {
        @Override
        protected String selectProtocol(List<String> requestedProtocols, WebSocketHandler webSocketHandler) {
            return requestedProtocols.isEmpty() ? null : requestedProtocols.getFirst();
        }
    }
}
