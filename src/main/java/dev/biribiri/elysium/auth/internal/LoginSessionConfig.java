package dev.biribiri.elysium.auth.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializationFailedException;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.session.jdbc.PostgreSqlJdbcIndexedSessionRepositoryCustomizer;

/**
 * Login sessions are stored in the database by Spring Session, so that commands can end them, and they survive restarts and are
 * shared between instances.
 */
@Configuration(proxyBeanMethods = false)
class LoginSessionConfig {

    private static final Logger log = LoggerFactory.getLogger(LoginSessionConfig.class);

    /**
     * Stops concurrent requests that add the same attribute to a session from failing on a duplicate key.
     */
    @Bean
    PostgreSqlJdbcIndexedSessionRepositoryCustomizer postgreSqlSessionQueries() {
        return new PostgreSqlJdbcIndexedSessionRepositoryCustomizer();
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
                log.warn("Ignoring a login session attribute that can't be read", e);
                return null;
            }
        };
    }
}
