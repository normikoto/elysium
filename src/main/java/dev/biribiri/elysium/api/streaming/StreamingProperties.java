package dev.biribiri.elysium.api.streaming;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param maxConnections how many streaming connections can be open at once, from everyone together, since anyone can open
 *                       one without a token, and each holds memory until it closes
 */
@ConfigurationProperties("elysium.streaming")
public record StreamingProperties(@DefaultValue("1000") int maxConnections) {
}
