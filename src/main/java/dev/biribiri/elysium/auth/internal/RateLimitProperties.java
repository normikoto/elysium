package dev.biribiri.elysium.auth.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param enabled whether logins and sign-ups are rate limited
 */
@ConfigurationProperties("elysium.rate-limit")
public record RateLimitProperties(@DefaultValue("true") boolean enabled) {
}
