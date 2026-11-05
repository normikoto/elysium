package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.Ids;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializationFailedException;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.jdbc.PostgreSqlJdbcIndexedSessionRepositoryCustomizer;
import org.springframework.session.web.http.CookieSerializer;

import java.util.List;

/**
 * Login sessions are stored in the database by Spring Session, so that commands can end them, and they survive restarts and are
 * shared between instances.
 */
@Configuration(proxyBeanMethods = false)
class LoginSessionConfig {

    private static final Logger log = LoggerFactory.getLogger(LoginSessionConfig.class);

    /**
     * What's stored for a session's ID, given the ID as a parameter. IDs are random UUIDs, so there's nothing to guess, which is why
     * this can be a plain hash and not a slow one.
     */
    private static final String STORED_ID = "encode(sha256(convert_to(%s, 'UTF8')), 'hex')";

    /**
     * Stops concurrent requests that add the same attribute to a session from failing on a duplicate key.
     */
    @Bean
    PostgreSqlJdbcIndexedSessionRepositoryCustomizer postgreSqlSessionQueries() {
        return new PostgreSqlJdbcIndexedSessionRepositoryCustomizer();
    }

    /**
     * Stores only a hash of each session's ID, so that someone who can read the database or a backup can't use what's there to take
     * over a session. The ID itself stays in the cookie and in memory. These are Spring Session's own queries, other than that.
     * Finding a session returns the ID it was asked for, because that's what it then writes back, and what goes in the cookie.
     *
     * <p>Spring Session's query for finding sessions by principal would return hashes as IDs. Nothing uses it, because ending an
     * account's sessions is {@link LocalAccounts#endSessions}. Statement logging that includes parameters would log IDs, as it
     * would log anything else.
     */
    @Bean
    SessionRepositoryCustomizer<JdbcIndexedSessionRepository> hashedSessionIds() {
        return sessions -> {
            sessions.setCreateSessionQuery("""
                    INSERT INTO %TABLE_NAME% (PRIMARY_ID, SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME, MAX_INACTIVE_INTERVAL, EXPIRY_TIME, PRINCIPAL_NAME)
                    VALUES (?, """ + STORED_ID.formatted("?") + """
                    , ?, ?, ?, ?, ?)
                    """);
            sessions.setGetSessionQuery("""
                    SELECT S.PRIMARY_ID, REQUESTED.ID AS SESSION_ID, S.CREATION_TIME, S.LAST_ACCESS_TIME, S.MAX_INACTIVE_INTERVAL, SA.ATTRIBUTE_NAME, SA.ATTRIBUTE_BYTES
                    FROM (SELECT CAST(? AS TEXT) AS ID) REQUESTED
                    JOIN %TABLE_NAME% S ON S.SESSION_ID = """ + STORED_ID.formatted("REQUESTED.ID") + """

                    LEFT JOIN %TABLE_NAME%_ATTRIBUTES SA ON S.PRIMARY_ID = SA.SESSION_PRIMARY_ID
                    """);
            sessions.setUpdateSessionQuery("""
                    UPDATE %TABLE_NAME%
                    SET SESSION_ID = """ + STORED_ID.formatted("?") + """
                    , LAST_ACCESS_TIME = ?, MAX_INACTIVE_INTERVAL = ?, EXPIRY_TIME = ?, PRINCIPAL_NAME = ?
                    WHERE PRIMARY_ID = ?
                    """);
            sessions.setDeleteSessionQuery("""
                    DELETE FROM %TABLE_NAME%
                    WHERE SESSION_ID = """ + STORED_ID.formatted("?") + """

                    AND MAX_INACTIVE_INTERVAL >= 0
                    """);
        };
    }

    /**
     * Session IDs are UUIDs, so a cookie with anything else in it isn't a session. Without this, it would go to the database, which
     * refuses text with a zero byte in it, so that request would get an error rather than being treated as logged out.
     */
    @Bean
    static BeanPostProcessor ignoringCookiesThatArentSessionIds() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof CookieSerializer cookies ? new SessionIdCookies(cookies) : bean;
            }
        };
    }

    /**
     * Spring Session's usual conversions, except that an attribute that can't be read counts as missing. Attributes are Java
     * serialized, so upgrading Spring Security can leave old ones unreadable. The session is read on every page for anyone with a
     * cookie, including public pages, so otherwise those people would get an error everywhere until the session expired. This
     * way they're logged out. Spring Session only looks for this bean by name, and nothing else should get it by type.
     */
    @Bean(name = "springSessionConversionService", defaultCandidate = false)
    GenericConversionService springSessionConversionService(ConfigurableBeanFactory beanFactory) {
        var conversions = new GenericConversionService();
        conversions.addConverter(Object.class, byte[].class, new SerializingConverter());
        conversions.addConverter(byte[].class, Object.class, ignoringUnreadable(new DeserializingConverter(beanFactory.getBeanClassLoader())));
        return conversions;
    }

    private static Converter<byte[], Object> ignoringUnreadable(DeserializingConverter deserializer) {
        return bytes -> {
            try {
                return deserializer.convert(bytes);
            } catch (SerializationFailedException e) {
                // Not the whole stack trace, because this can happen for every request from everyone logged in after an upgrade.
                log.warn("Ignoring a login session attribute that can't be read: {}", e.getMostSpecificCause().toString());
                return null;
            }
        };
    }

    private record SessionIdCookies(CookieSerializer delegate) implements CookieSerializer {

        @Override
        public void writeCookieValue(CookieValue cookieValue) {
            delegate.writeCookieValue(cookieValue);
        }

        @Override
        public List<String> readCookieValues(HttpServletRequest request) {
            return delegate.readCookieValues(request).stream().filter(value -> Ids.parse(value).isPresent()).toList();
        }
    }
}
