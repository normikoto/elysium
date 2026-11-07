package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.moderation.DomainName;
import dev.biribiri.elysium.moderation.InvalidDomainBlockException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Limits on the activities that each server can have stored, which {@link ServerActivityLimits} enforces.
 *
 * @param activitiesPerMinute how many activities one server can deliver a minute
 * @param dataPerHour         how much one server's activities can add up to in an hour, counting their bodies. This can't
 *                            be less than {@link Inbox#MAX_BODY_BYTES}, or the largest activity could never be accepted.
 * @param exemptDomains       domains, along with their subdomains, that aren't limited, such as a large server an admin
 *                            trusts. Each is in {@link DomainName}'s form, and can't be a public suffix such as
 *                            {@code co.uk}, which would exempt everyone under it.
 */
@ConfigurationProperties("elysium.federation.inbox-limits")
public record InboxLimitProperties(
        @DefaultValue("" + DEFAULT_ACTIVITIES_PER_MINUTE) int activitiesPerMinute,
        @DefaultValue(DEFAULT_MEGABYTES_PER_HOUR + "MB") DataSize dataPerHour,
        @DefaultValue Set<String> exemptDomains) {

    public static final int DEFAULT_ACTIVITIES_PER_MINUTE = 120;
    public static final int DEFAULT_MEGABYTES_PER_HOUR = 20;

    private static final DataSize DEFAULT_DATA_PER_HOUR = DataSize.ofMegabytes(DEFAULT_MEGABYTES_PER_HOUR);
    private static final String EXEMPT_DOMAINS = "elysium.federation.inbox-limits.exempt-domains";

    public InboxLimitProperties {
        if (activitiesPerMinute < 1) {
            throw new IllegalArgumentException(
                    "elysium.federation.inbox-limits.activities-per-minute must be at least 1");
        }
        // A setting that's present but empty, such as from an empty variable in .env, binds as null.
        if (dataPerHour == null) {
            dataPerHour = DEFAULT_DATA_PER_HOUR;
        }
        if (dataPerHour.toBytes() < Inbox.MAX_BODY_BYTES) {
            throw new IllegalArgumentException(
                    "elysium.federation.inbox-limits.data-per-hour must be at least 1MB, the largest activity accepted");
        }
        exemptDomains = exemptDomains == null ? Set.of() : exemptDomains.stream()
                .map(String::strip)
                .filter(domain -> !domain.isEmpty())
                .map(InboxLimitProperties::parseExemptDomain)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String parseExemptDomain(String value) {
        String domain;
        try {
            domain = DomainName.parse(value);
        } catch (InvalidDomainBlockException e) {
            throw new IllegalArgumentException(
                    EXEMPT_DOMAINS + " has \"" + value + "\", which isn't a domain like example.com: " + e.getMessage(), e);
        }
        if (ServerActivityLimits.isPublicSuffix(domain)) {
            throw new IllegalArgumentException(EXEMPT_DOMAINS + " has \"" + value
                    + "\", which is a suffix that anyone can register domains under, so it would exempt all of them");
        }
        return domain;
    }
}
