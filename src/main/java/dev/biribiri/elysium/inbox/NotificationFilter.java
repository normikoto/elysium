package dev.biribiri.elysium.inbox;

import java.util.List;
import java.util.UUID;

public record NotificationFilter(List<String> types, List<String> excludeTypes, UUID accountId) {
    public NotificationFilter {
        types = types == null ? List.of() : List.copyOf(types);
        excludeTypes = excludeTypes == null ? List.of() : List.copyOf(excludeTypes);
    }
}
