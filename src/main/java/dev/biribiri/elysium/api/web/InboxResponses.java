package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.inbox.Conversation;
import dev.biribiri.elysium.inbox.Notification;
import dev.biribiri.elysium.inbox.NotificationGroup;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
class InboxResponses {
    private final ActorService actors;
    private final AccountResponses accounts;
    private final StatusService statuses;
    private final StatusResponses statusResponses;
    private final DomainBlocks blocks;

    InboxResponses(ActorService actors, AccountResponses accounts, StatusService statuses,
                   StatusResponses statusResponses, DomainBlocks blocks) {
        this.actors = actors;
        this.accounts = accounts;
        this.statuses = statuses;
        this.statusResponses = statusResponses;
        this.blocks = blocks;
    }

    List<NotificationResponse> notifications(UUID viewer, List<Notification> found) {
        var accountMap = accounts(found.stream().map(Notification::accountId).toList());
        var statusMap = statuses(viewer, found.stream().map(Notification::statusId).filter(Objects::nonNull).toList());
        return found.stream().filter(n -> accountMap.containsKey(n.accountId()))
                .filter(n -> n.statusId() == null || statusMap.containsKey(n.statusId()))
                .map(n -> new NotificationResponse(n.id().toString(), n.type(), ApiTimestamps.format(n.createdAt()),
                        accountMap.get(n.accountId()), statusMap.get(n.statusId()),
                        n.groupKey() == null ? "ungrouped-" + n.id() : n.groupKey())).toList();
    }

    GroupedNotificationsResponse groups(UUID viewer, List<NotificationGroup> found, boolean partial) {
        var accountMap = accounts(found.stream().flatMap(g -> g.sampleAccountIds().stream()).distinct().toList());
        var statusMap = statuses(viewer, found.stream().map(NotificationGroup::statusId).filter(Objects::nonNull).distinct().toList());
        var visible = found.stream().filter(g -> g.statusId() == null || statusMap.containsKey(g.statusId()))
                .filter(g -> g.sampleAccountIds().stream().anyMatch(accountMap::containsKey)).toList();
        var fullIds = new LinkedHashSet<UUID>();
        var allIds = new LinkedHashSet<UUID>();
        for (var group : visible) {
            var ids = group.sampleAccountIds().stream().filter(accountMap::containsKey).toList();
            allIds.addAll(ids);
            fullIds.addAll(partial ? ids.subList(0, 1) : ids);
        }
        return new GroupedNotificationsResponse(fullIds.stream().map(accountMap::get).toList(),
                partial ? allIds.stream().filter(id -> !fullIds.contains(id)).map(accountMap::get)
                        .map(GroupedNotificationsResponse.PartialAccount::from).toList() : null,
                visible.stream().map(NotificationGroup::statusId).filter(Objects::nonNull).distinct().map(statusMap::get).toList(),
                visible.stream().map(g -> NotificationGroupResponse.from(g, g.sampleAccountIds().stream()
                        .filter(accountMap::containsKey).map(UUID::toString).toList())).toList());
    }

    List<ConversationResponse> conversations(UUID viewer, List<Conversation> found) {
        var ids = new HashSet<UUID>();
        found.forEach(c -> ids.addAll(c.accountIds()));
        ids.add(viewer);
        var accountMap = accounts(ids);
        var statusMap = statuses(viewer, found.stream().map(Conversation::lastStatusId).toList());
        return found.stream().filter(c -> statusMap.containsKey(c.lastStatusId())).map(c -> {
            var people = c.accountIds().stream().map(accountMap::get).filter(Objects::nonNull).toList();
            if (people.isEmpty() && accountMap.containsKey(viewer)) people = List.of(accountMap.get(viewer));
            return new ConversationResponse(c.id().toString(), c.unread(), people, statusMap.get(c.lastStatusId()));
        }).toList();
    }

    List<AccountResponse> accountList(List<UUID> ids) {
        var found = accounts(ids);
        return ids.stream().map(found::get).filter(Objects::nonNull).toList();
    }

    private Map<UUID, AccountResponse> accounts(Collection<UUID> ids) {
        var found = actors.findByIds(ids).stream().filter(Actor::isActive).toList();
        var blocked = blocks.blockedHosts(found.stream().filter(a -> !actors.isLocal(a)).map(Actor::domain).toList());
        return accounts.byId(found.stream().filter(a -> actors.isLocal(a) || !blocked.contains(a.domain())).toList());
    }

    private Map<UUID, StatusResponse> statuses(UUID viewer, Collection<UUID> ids) {
        var found = statuses.findVisible(ids, viewer);
        return statusResponses.list(found).stream().collect(Collectors.toMap(s -> UUID.fromString(s.id()), Function.identity()));
    }
}
