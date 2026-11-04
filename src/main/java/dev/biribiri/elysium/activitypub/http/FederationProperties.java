package dev.biribiri.elysium.activitypub.http;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * @param allowedPrivateNetworks networks in CIDR form that requests to other servers may reach, even though their
 *                               addresses aren't public. This is for servers on a private network, such as the federation
 *                               test lab, and everything else about a request stays the same.
 */
@ConfigurationProperties("elysium.federation")
public record FederationProperties(@DefaultValue List<String> allowedPrivateNetworks) {
}
