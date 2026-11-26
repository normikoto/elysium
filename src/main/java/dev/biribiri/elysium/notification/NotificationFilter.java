package dev.biribiri.elysium.notification;

import java.util.List;
import java.util.UUID;

/**
 * @param includeFiltered whether notifications that a {@link NotificationPolicy} filtered are included
 */
public record NotificationFilter(List<String> types, List<String> excludeTypes, UUID accountId, boolean includeFiltered) {
    public NotificationFilter {
        types = types == null ? List.of() : List.copyOf(types);
        excludeTypes = excludeTypes == null ? List.of() : List.copyOf(excludeTypes);
    }

    public NotificationFilter withoutFiltered() {
        return new NotificationFilter(types, excludeTypes, accountId, false);
    }
}
