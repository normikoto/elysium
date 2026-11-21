package dev.biribiri.elysium.actor;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class ActorRemoval implements ActorDataRemoval {

    private final ProfileService profiles;
    private final AccountMigrations migrations;

    ActorRemoval(ProfileService profiles, AccountMigrations migrations) {
        this.profiles = profiles;
        this.migrations = migrations;
    }

    @Override
    public void removeForDeleted(UUID actorId) {
        profiles.removeAll(actorId);
        migrations.removeForActor(actorId);
    }

    @Override
    public void removeForDomain(String domain) {
        profiles.removeForDomain(domain);
        migrations.removeForDomain(domain);
    }
}
