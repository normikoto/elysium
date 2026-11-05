package dev.biribiri.elysium;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.WebUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final String SESSION_COOKIE = "SESSION";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
    }

    /**
     * Lets a test keep using a {@code MockHttpSession} to stay logged in across requests. Spring Session only looks at cookies, and
     * would ignore it, so this makes the {@code MockHttpSession} act as a browser's cookie jar. Requests go through the real
     * cookies and the sessions in the database. This runs before Spring Session's filter, and does nothing for a request without a
     * {@code MockHttpSession}.
     */
    @Bean
    FilterRegistrationBean<Filter> mockSessionCookieJar() {
        Filter filter = (request, response, chain) -> {
            var mockRequest = WebUtils.getNativeRequest(request, MockHttpServletRequest.class);
            var mockResponse = WebUtils.getNativeResponse(response, MockHttpServletResponse.class);
            var jar = mockRequest == null ? null : mockRequest.getSession(false);
            if (jar == null) {
                chain.doFilter(request, response);
                return;
            }
            if (jar.getAttribute(SESSION_COOKIE) instanceof String value) {
                var cookies = new ArrayList<>(mockRequest.getCookies() == null ? List.of() : Arrays.asList(mockRequest.getCookies()));
                cookies.add(new Cookie(SESSION_COOKIE, value));
                mockRequest.setCookies(cookies.toArray(Cookie[]::new));
            }
            chain.doFilter(request, response);
            var written = mockResponse == null ? null : mockResponse.getCookie(SESSION_COOKIE);
            if (written != null) {
                jar.setAttribute(SESSION_COOKIE, written.getMaxAge() == 0 ? null : written.getValue());
            }
        };
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
