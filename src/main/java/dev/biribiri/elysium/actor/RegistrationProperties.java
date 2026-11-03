package dev.biribiri.elysium.actor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param enabled whether anyone can create an account at the sign-up page
 */
@ConfigurationProperties("elysium.registration")
public record RegistrationProperties(@DefaultValue("false") boolean enabled) {
}
