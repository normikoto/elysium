package dev.biribiri.elysium;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final String COOKIES = TestcontainersConfiguration.class.getName() + ".cookies";

    /**
     * One container for the whole test JVM, rather than one per Spring context. Test classes need dozens of different contexts,
     * and Spring caches up to 32 of them, so a container each meant as many published ports. Rootless Docker picks a port that's
     * free in its own namespace, and then fails if it's taken on the host, so every port was another chance of a clash. With one
     * container, a clash can be retried by starting it again.
     */
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
            .withStartupAttempts(3)
            // Every cached context keeps its own connection pool open in the same server. Setting a command replaces
            // Testcontainers' own, so fsync=off has to be repeated.
            .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=1000");

    private static final AtomicInteger DATABASES = new AtomicInteger();

    /**
     * Gives each context a database of its own, so it still starts empty, as it did with a container of its own.
     */
    @Bean
    JdbcConnectionDetails jdbcConnectionDetails() throws SQLException {
        var name = startedPostgres().getDatabaseName() + "_" + DATABASES.incrementAndGet();
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        var url = "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), name);
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return POSTGRES.getUsername();
            }

            @Override
            public String getPassword() {
                return POSTGRES.getPassword();
            }

            @Override
            public String getJdbcUrl() {
                return url;
            }
        };
    }

    private static synchronized PostgreSQLContainer startedPostgres() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        return POSTGRES;
    }

    /**
     * Stands in for an SMTP server, which also lets tests use the registration modes that need one.
     */
    @Bean
    @Primary
    RecordingMailer recordingMailer() {
        return new RecordingMailer();
    }

    /**
     * Lets a test keep using a {@code MockHttpSession} to stay logged in across requests. Spring Session only looks at cookies, and
     * would ignore it, so this makes the {@code MockHttpSession} act as a browser's cookie jar, for the session cookie and the
     * others a visitor gets before logging in. Requests go through the real cookies and the sessions in the database. This runs
     * before Spring Session's filter, and does nothing for a request without a {@code MockHttpSession}.
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
            @SuppressWarnings("unchecked")
            var kept = jar.getAttribute(COOKIES) instanceof Map<?, ?> map
                    ? new LinkedHashMap<>((Map<String, String>) map)
                    : new LinkedHashMap<String, String>();
            if (!kept.isEmpty()) {
                var cookies = new ArrayList<>(mockRequest.getCookies() == null ? List.of() : Arrays.asList(mockRequest.getCookies()));
                var sent = cookies.stream().map(Cookie::getName).collect(Collectors.toSet());
                kept.forEach((name, value) -> {
                    if (!sent.contains(name)) {
                        cookies.add(new Cookie(name, value));
                    }
                });
                mockRequest.setCookies(cookies.toArray(Cookie[]::new));
            }
            chain.doFilter(request, response);
            if (mockResponse != null) {
                for (var written : mockResponse.getCookies()) {
                    if (written.getMaxAge() == 0 || written.getValue().isEmpty()) {
                        kept.remove(written.getName());
                    } else {
                        kept.put(written.getName(), written.getValue());
                    }
                }
                jar.setAttribute(COOKIES, kept);
            }
        };
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * Gives a MockMvc multipart request the length a real client would send with it. MockMvc never encodes the body, so it
     * has none, and the server refuses a multipart request without one. This counts the files and values, which is close
     * enough to the encoded size for the limits tests use.
     */
    @Bean
    FilterRegistrationBean<Filter> mockMultipartLength() {
        Filter filter = (request, response, chain) -> {
            var multipart = WebUtils.getNativeRequest(request, MockMultipartHttpServletRequest.class);
            if (multipart == null || multipart.getContentLengthLong() >= 0) {
                chain.doFilter(request, response);
                return;
            }
            long length = 0;
            for (var files : multipart.getMultiFileMap().values()) {
                for (var file : files) {
                    length += file.getSize();
                }
            }
            for (var values : multipart.getParameterMap().values()) {
                for (var value : values) {
                    length += value.getBytes(StandardCharsets.UTF_8).length;
                }
            }
            var declared = length;
            chain.doFilter(new HttpServletRequestWrapper((HttpServletRequest) request) {
                @Override
                public int getContentLength() {
                    return (int) Math.min(declared, Integer.MAX_VALUE);
                }

                @Override
                public long getContentLengthLong() {
                    return declared;
                }
            }, response);
        };
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
