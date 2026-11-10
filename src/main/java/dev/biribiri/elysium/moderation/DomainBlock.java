package dev.biribiri.elysium.moderation;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A server that this one has cut off or limited, along with its subdomains.
 *
 * @param domain         the domain, in the form {@link DomainName#parse} gives
 * @param privateComment a note for admins, or null
 * @param severity       whether it's cut off, or only limited by what's below
 * @param rejectMedia    whether its attachments are kept from being downloaded, which a suspension does anyway
 */
public record DomainBlock(UUID id, String domain, String privateComment, Instant createdAt, Severity severity,
                          boolean rejectMedia) {

    public enum Severity {
        /** Nothing is taken from it, and nothing is stored from it. */
        SUSPEND,
        /** It federates as usual, apart from what the block's other settings limit. */
        NOOP;

        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * @throws InvalidDomainBlockException for anything else, such as {@code silence}, which isn't supported
         */
        public static Severity fromValue(String value) {
            return switch (value) {
                case "suspend" -> SUSPEND;
                case "noop" -> NOOP;
                case null, default -> throw new InvalidDomainBlockException(
                        "Severity has to be suspend or noop.");
            };
        }
    }

    public DomainBlock {
        Objects.requireNonNull(severity);
    }

    /**
     * @return whether its attachments are rejected, either by the block or because it's suspended
     */
    public boolean rejectsMedia() {
        return rejectMedia || severity == Severity.SUSPEND;
    }

    /**
     * @return whether this does more than another block, so that it's worth having on a subdomain of the other's
     */
    public boolean stricterThan(DomainBlock other) {
        if (severity != other.severity) {
            return severity == Severity.SUSPEND;
        }
        return rejectsMedia() && !other.rejectsMedia();
    }
}
