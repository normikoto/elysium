package dev.biribiri.elysium.push;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Whose notifications are pushed, judged by who sent them.
 */
public enum PushPolicy {
    ALL, FOLLOWED, FOLLOWER, NONE;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<PushPolicy> fromValue(String value) {
        return Arrays.stream(values()).filter(policy -> policy.value().equals(value)).findFirst();
    }
}
