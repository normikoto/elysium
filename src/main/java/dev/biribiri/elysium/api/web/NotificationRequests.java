package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.IdPage;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.notification.NotificationFilter;
import org.springframework.util.MultiValueMap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class NotificationRequests {
    private NotificationRequests() {
    }

    static UUID viewer(TokenPrincipal token) {
        return token.actorId().orElseThrow(InvalidRequestException::requiresUser);
    }

    static UUID id(String id) {
        return RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
    }

    static UUID cursor(MultiValueMap<String, String> values, String name) {
        var value = values.getFirst(name);
        return value == null || value.isEmpty() ? null : RequestParameters.uuid(value)
                .orElseThrow(() -> new InvalidRequestException("Validation failed: " + name + " is not valid"));
    }

    static IdPage page(MultiValueMap<String, String> values) {
        return RequestParameters.page(values.getFirst("limit"), values.getFirst("max_id"), values.getFirst("since_id"),
                values.getFirst("min_id"), PageLimits.NOTIFICATIONS, PageLimits.MAX_NOTIFICATIONS);
    }

    static IdPage conversationPage(MultiValueMap<String, String> values) {
        return RequestParameters.page(values.getFirst("limit"), values.getFirst("max_id"), values.getFirst("since_id"),
                values.getFirst("min_id"), PageLimits.CONVERSATIONS, PageLimits.MAX_CONVERSATIONS);
    }

    static int countLimit(MultiValueMap<String, String> values) {
        return RequestParameters.number("limit", values.getFirst("limit"), 100);
    }

    static NotificationFilter filter(MultiValueMap<String, String> values) {
        return new NotificationFilter(array(values, "types"), array(values, "exclude_types"), cursor(values, "account_id"));
    }

    static List<String> array(MultiValueMap<String, String> values, String name) {
        return values.getOrDefault(name + "[]", values.getOrDefault(name, List.of()));
    }

    static boolean partial(MultiValueMap<String, String> values) {
        var expand = values.getFirst("expand_accounts");
        if (expand == null || expand.equals("full")) return false;
        if (expand.equals("partial_avatars")) return true;
        throw new InvalidRequestException("Validation failed: expand_accounts must be full or partial_avatars");
    }

    static Map<String, List<String>> filters(MultiValueMap<String, String> values) {
        var result = new LinkedHashMap<String, List<String>>();
        for (var name : List.of("types", "exclude_types", "grouped_types", "supported_types")) {
            var array = array(values, name);
            if (!array.isEmpty()) result.put(name + "[]", array);
        }
        for (var name : List.of("account_id", "include_filtered", "expand_accounts")) {
            if (values.containsKey(name)) result.put(name, values.get(name));
        }
        return result;
    }
}
