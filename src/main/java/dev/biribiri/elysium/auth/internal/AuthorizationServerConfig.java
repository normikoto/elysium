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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
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
     * Each hash runs on one CPU, so more at once than there are CPUs would only make them all slower.
     */
    private static final int MAX_CONCURRENT_HASHES = Math.max(2, Runtime.getRuntime().availableProcessors());
    private static final Duration MAX_HASH_WAIT = Duration.ofSeconds(5);

    /**
     * Hashes passwords. Hashes carry the encoder ID as a prefix, so the parameters can change later without invalidating
     * existing hashes. Client secrets use {@link ClientSecretEncoder} instead.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        var argon2id = new Argon2PasswordEncoder(
                ARGON2_SALT_BYTES, ARGON2_HASH_BYTES, ARGON2_PARALLELISM, ARGON2_MEMORY_KIB, ARGON2_ITERATIONS);
        return new ConcurrencyLimitedPasswordEncoder(
                new DelegatingPasswordEncoder(PASSWORD_ENCODER_ID, Map.of(PASSWORD_ENCODER_ID, argon2id)),
                MAX_CONCURRENT_HASHES,
                MAX_HASH_WAIT);
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcOperations jdbc) {
        return new JdbcRegisteredClientRepository(jdbc);
    }

    @Bean
    OAuth2AuthorizationService authorizationService(
            JdbcOperations jdbc, RegisteredClientRepository clients, PlatformTransactionManager transactionManager,
            AuthorizedClients authorizedClients) {
        return new SingleConnectionAuthorizationService(
                new JdbcOAuth2AuthorizationService(jdbc, clients), new TransactionTemplate(transactionManager),
                authorizedClients);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcOperations jdbc, RegisteredClientRepository clients, PlatformTransactionManager transactionManager,
            AuthorizedClients authorizedClients) {
        return new SingleConnectionAuthorizationConsentService(
                new JdbcOAuth2AuthorizationConsentService(jdbc, clients), new TransactionTemplate(transactionManager),
                authorizedClients);
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
