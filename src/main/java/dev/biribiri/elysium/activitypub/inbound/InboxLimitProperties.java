package dev.biribiri.elysium.activitypub.inbound;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.util.List;
import java.util.Locale;

/**
 * Limits on the activities that each server can have stored, which {@link ServerActivityLimits} enforces.
 *
 * @param activitiesPerMinute how many activities one server can deliver a minute
 * @param dataPerHour         how much one server's activities can add up to in an hour, counting their bodies. This can't
 *                            be less than {@link Inbox#MAX_BODY_BYTES}, or the largest activity could never be accepted.
 * @param exemptDomains       domains, along with their subdomains, that aren't limited, such as a large server an admin
 *                            trusts. Each is lowercased, without a trailing dot.
 */
@ConfigurationProperties("elysium.federation.inbox-limits")
public record InboxLimitProperties(
        @DefaultValue("120") int activitiesPerMinute,
        @DefaultValue("20MB") DataSize dataPerHour,
        @DefaultValue List<String> exemptDomains) {

    public InboxLimitProperties {
        if (activitiesPerMinute < 1) {
            throw new IllegalArgumentException(
                    "elysium.federation.inbox-limits.activities-per-minute must be at least 1");
        }
        if (dataPerHour.toBytes() < Inbox.MAX_BODY_BYTES) {
            throw new IllegalArgumentException(
                    "elysium.federation.inbox-limits.data-per-hour must be at least 1MB, the largest activity accepted");
        }
        exemptDomains = exemptDomains.stream()
                .map(String::strip)
                .filter(domain -> !domain.isEmpty())
                .map(domain -> domain.toLowerCase(Locale.ROOT))
                .map(domain -> domain.endsWith(".") ? domain.substring(0, domain.length() - 1) : domain)
                .toList();
    }
}
