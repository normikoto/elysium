package dev.biribiri.elysium.actor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param publish whether the server publishes how many accounts are active, and its weekly counts of statuses, logins, and
 *                sign-ups. On a server with few users these show when each of them is around, so an operator can turn them
 *                off. Use is recorded either way, so that turning this back on has the numbers straight away.
 */
@ConfigurationProperties("elysium.usage")
public record UsageProperties(@DefaultValue("true") boolean publish) {
}
