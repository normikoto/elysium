package dev.biribiri.elysium.moderation;

import java.net.IDN;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Puts domains in the one form that blocks are stored and compared in: lowercase ASCII, with no port or trailing dot. This
 * doesn't use {@code HostHeader}, which defines the {@code Host} header that signatures cover, and keeps the port.
 */
final class DomainName {

    private static final int MAX_LENGTH = 253;

    private static final String NOT_A_DOMAIN = "Enter only a domain, like example.com, with no wildcard, port, or URL parts.";

    /**
     * The most labels a name can have within {@value MAX_LENGTH} characters. A longer host can't exist, but it's still
     * compared by its last labels, so that padding one with more subdomains can't get it past a block.
     */
    private static final int MAX_SUFFIXES = 127;

    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    /**
     * Browsers treat a host as an IP address if its last label is a number, in decimal or hexadecimal.
     */
    private static final Pattern NUMERIC_LABEL = Pattern.compile("[0-9]+|0x[0-9a-f]*");

    private DomainName() {
    }

    /**
     * Reads a domain that an admin wants to block.
     *
     * @return the domain in the form that's stored
     * @throws InvalidDomainBlockException if it isn't a domain that can be blocked
     */
    static String parse(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidDomainBlockException("Enter a domain to block.");
        }
        var name = withoutTrailingDot(value.strip());
        String ascii;
        try {
            ascii = IDN.toASCII(name, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new InvalidDomainBlockException(NOT_A_DOMAIN);
        }
        if (ascii.length() > MAX_LENGTH) {
            throw new InvalidDomainBlockException("That domain is too long.");
        }
        var labels = ascii.split("\\.", -1);
        for (var label : labels) {
            if (!LABEL.matcher(label).matches()) {
                throw new InvalidDomainBlockException(NOT_A_DOMAIN);
            }
        }
        if (NUMERIC_LABEL.matcher(labels[labels.length - 1]).matches()) {
            throw new InvalidDomainBlockException("IP addresses can't be blocked, only domains.");
        }
        if (labels.length < 2) {
            throw new InvalidDomainBlockException("Blocking a whole top-level domain isn't allowed.");
        }
        return ascii;
    }

    /**
     * Reads a host from another server, with or without a port. This comes from outside, so it never fails.
     *
     * @return the host in the form that's stored, or empty if a block can't apply to it, such as for an IP address
     */
    static Optional<String> normalizeHost(String host) {
        if (host == null || host.isEmpty() || host.startsWith("[")) {
            return Optional.empty();
        }
        var name = withoutTrailingDot(withoutPort(host));
        String ascii;
        try {
            ascii = IDN.toASCII(name).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            // Too long for IDN, which doesn't matter, because only its last labels are compared. Other servers can't send
            // anything here that isn't ASCII, because URI.getHost returns null for it.
            ascii = name.toLowerCase(Locale.ROOT);
        }
        var last = ascii.substring(ascii.lastIndexOf('.') + 1);
        return last.isEmpty() || NUMERIC_LABEL.matcher(last).matches() ? Optional.empty() : Optional.of(ascii);
    }

    /**
     * @param domain a domain in the form that's stored
     * @return the domain and each domain above it that a block could be on, which leaves out the top level
     */
    static List<String> suffixes(String domain) {
        var suffixes = new ArrayList<String>();
        // Starts from the top level, so that the cap can only leave out the longest ones.
        var dot = domain.lastIndexOf('.');
        while (dot > 0 && suffixes.size() < MAX_SUFFIXES) {
            dot = domain.lastIndexOf('.', dot - 1);
            suffixes.add(domain.substring(dot + 1));
        }
        return suffixes;
    }

    private static String withoutPort(String host) {
        var colon = host.lastIndexOf(':');
        return colon == -1 ? host : host.substring(0, colon);
    }

    private static String withoutTrailingDot(String name) {
        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }
}
