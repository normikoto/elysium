package dev.biribiri.elysium.auth.internal;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/**
 * Finds requests that came in on the management server, when {@code management.server.port} gives it a port of its own.
 * Spring Boot applies the main server's security chains to it as well, so without this it would deny metrics as the main
 * port does. This takes the port the server actually listens on, rather than the setting, which can be 0 for any free
 * port.
 */
@Component
class ManagementPort implements ApplicationListener<WebServerInitializedEvent>, RequestMatcher {

    private static final String MANAGEMENT_NAMESPACE = "management";

    private volatile int port = -1;

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if (MANAGEMENT_NAMESPACE.equals(event.getApplicationContext().getServerNamespace())) {
            port = event.getWebServer().getPort();
        }
    }

    /**
     * Never true while the management server shares the main port, so that metrics are never served to the internet.
     */
    @Override
    public boolean matches(HttpServletRequest request) {
        return port > 0 && request.getLocalPort() == port;
    }
}
