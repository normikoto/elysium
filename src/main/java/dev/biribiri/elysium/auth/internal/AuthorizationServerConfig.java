package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.InstanceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
class AuthorizationServerConfig {

    private static final String PASSWORD_ENCODER_ID = "argon2id";
    private static final int ARGON2_SALT_BYTES = 16;
    private static final int ARGON2_HASH_BYTES = 32;
    private static final int ARGON2_PARALLELISM = 1;
    private static final int ARGON2_MEMORY_KIB = 19 * 1024;
    private static final int ARGON2_ITERATIONS = 2;

    /**
     * Hashes both passwords and client secrets. Hashes carry the encoder ID as a prefix, so the parameters can change
     * later without invalidating existing hashes. There must only be one of these beans, because Spring Authorization
     * Server silently falls back to its own encoder otherwise.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        var argon2id = new Argon2PasswordEncoder(
                ARGON2_SALT_BYTES, ARGON2_HASH_BYTES, ARGON2_PARALLELISM, ARGON2_MEMORY_KIB, ARGON2_ITERATIONS);
        return new DelegatingPasswordEncoder(PASSWORD_ENCODER_ID, Map.of(PASSWORD_ENCODER_ID, argon2id));
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcOperations jdbc) {
        return new JdbcRegisteredClientRepository(jdbc);
    }

    @Bean
    OAuth2AuthorizationService authorizationService(JdbcOperations jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcOperations jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(InstanceProperties instance) {
        return AuthorizationServerSettings.builder()
                .issuer(instance.baseUrl().toString())
                .authorizationEndpoint(SecurityConfig.AUTHORIZE_PATH)
                .tokenEndpoint(SecurityConfig.TOKEN_PATH)
                .tokenRevocationEndpoint(SecurityConfig.REVOKE_PATH)
                .build();
    }
}
