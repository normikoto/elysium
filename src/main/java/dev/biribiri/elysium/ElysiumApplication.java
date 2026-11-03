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
        if (!CommandRunner.isCommand(args)) {
            SpringApplication.run(ElysiumApplication.class, args);
            return;
        }

        // System properties override the operator's config and environment. A command can run alongside the server, so
        // it can't open the same ports. Without the quieter logging, a password prompt gets lost in startup messages.
        System.setProperty("server.port", "-1");
        System.setProperty("management.server.port", "-1");
        System.setProperty("spring.main.banner-mode", "off");
        System.setProperty("logging.level.root", "ERROR");
        System.exit(SpringApplication.exit(SpringApplication.run(ElysiumApplication.class, args)));
    }

}
