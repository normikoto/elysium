package dev.biribiri.elysium;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.oauth2.server.authorization.autoconfigure.servlet.OAuth2AuthorizationServerJwtAutoConfiguration;

// Access tokens are opaque, so the signing key this would generate at startup is never used.
@SpringBootApplication(exclude = OAuth2AuthorizationServerJwtAutoConfiguration.class)
@ConfigurationPropertiesScan
public class ElysiumApplication {

    public static void main(String[] args) {
        SpringApplication.run(ElysiumApplication.class, args);
    }

}
