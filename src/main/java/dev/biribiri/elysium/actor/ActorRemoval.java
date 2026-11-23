package dev.biribiri.elysium.actor;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class ActorRemoval implements ActorDataRemoval {

    private final ProfileService profiles;
    private final AccountMigrations migrations;
    private final ActiveAccounts activeAccounts;

    ActorRemoval(ProfileService profiles, AccountMigrations migrations, ActiveAccounts activeAccounts) {
        this.profiles = profiles;
        this.migrations = migrations;
        this.activeAccounts = activeAccounts;
    }

    @Override
    public void removeForDeleted(UUID actorId) {
        profiles.removeAll(actorId);
        migrations.removeForActor(actorId);
        activeAccounts.removeForActor(actorId);
    }

    @Override
    public void removeForDomain(String domain) {
        profiles.removeForDomain(domain);
        migrations.removeForDomain(domain);
    }
}
