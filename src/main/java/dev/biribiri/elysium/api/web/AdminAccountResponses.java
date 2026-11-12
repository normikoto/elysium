package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.auth.AccountActions;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
class AdminAccountResponses {

    private final ActorService actors;
    private final AccountActions accounts;
    private final AccountResponses accountResponses;

    AdminAccountResponses(ActorService actors, AccountActions accounts, AccountResponses accountResponses) {
        this.actors = actors;
        this.accounts = accounts;
        this.accountResponses = accountResponses;
    }

    AdminAccountResponse one(Actor account) {
        return list(List.of(account)).getFirst();
    }

    List<AdminAccountResponse> list(Collection<Actor> found) {
        var ids = found.stream().map(Actor::id).toList();
        var admins = accounts.adminsAmong(ids);
        var emails = accounts.emailsOf(ids);
        var built = accountResponses.byId(found);
        return found.stream()
                .map(account -> from(
                        account, built.get(account.id()), admins.contains(account.id()), emails.get(account.id())))
                .toList();
    }

    /**
     * @return a {@link java.util.HashMap}, so that looking up a null ID finds nothing
     */
    Map<UUID, AdminAccountResponse> byId(Collection<Actor> found) {
        var accounts = List.copyOf(found);
        var responses = list(accounts);
        var byId = new HashMap<UUID, AdminAccountResponse>();
        for (var i = 0; i < accounts.size(); i++) {
            byId.put(accounts.get(i).id(), responses.get(i));
        }
        return byId;
    }

    private AdminAccountResponse from(Actor account, AccountResponse response, boolean admin, String email) {
        var local = actors.isLocal(account);
        return new AdminAccountResponse(
                account.id().toString(),
                account.username(),
                local ? null : account.domain(),
                email,
                ApiTimestamps.format(account.createdAt()),
                local ? new AdminAccountResponse.Role(admin ? "Admin" : "User") : null,
                !account.isActive(),
                response);
    }
}
