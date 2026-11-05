package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.auth.AccountActionRefusedException;
import dev.biribiri.elysium.auth.AccountActions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
class AccountModeration implements AccountActions {

    private final ActorService actors;
    private final LocalAccounts accounts;
    private final TransactionTemplate transaction;

    AccountModeration(ActorService actors, LocalAccounts accounts, PlatformTransactionManager transactionManager) {
        this.actors = actors;
        this.accounts = accounts;
        this.transaction = new TransactionTemplate(transactionManager);
        // Whatever the database's default is, counting admins has to see what's been committed while waiting for the lock.
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public boolean suspend(UUID accountId, Optional<UUID> actingAdmin) {
        var account = find(accountId);
        if (account.isEmpty()) {
            return false;
        }
        refuseIfSelf(accountId, actingAdmin, "You can't suspend your own account.");
        transaction.executeWithoutResult(status -> {
            refuseIfLastAdmin(accountId, "This server's last admin can't be suspended. Choose another admin first.");
            actors.suspend(accountId);
            // Otherwise a session that isn't used during the suspension would work again once it ends.
            accounts.endSessions(accountId);
        });
        return true;
    }

    @Override
    public boolean unsuspend(UUID accountId) {
        var account = find(accountId);
        if (account.isEmpty()) {
            return false;
        }
        if (!actors.unsuspend(accountId)) {
            throw new AccountActionRefusedException("That account isn't suspended.");
        }
        return true;
    }

    @Override
    public boolean delete(UUID accountId, Optional<UUID> actingAdmin) {
        var account = find(accountId);
        if (account.isEmpty()) {
            return false;
        }
        if (!actors.isLocal(account.get())) {
            throw new AccountActionRefusedException(
                    "Only local accounts can be deleted, because another server has this one. Suspend it instead.");
        }
        refuseIfSelf(accountId, actingAdmin, "You can't delete your own account from here.");
        remove(accountId);
        return true;
    }

    /**
     * Deletes the account of whoever's logged in, which only needs their password.
     *
     * @return whether the password was right. If it wasn't, nothing has changed.
     * @throws AccountActionRefusedException if this is the server's last admin
     * @throws PasswordHashingBusyException  if too many passwords are being checked already
     */
    boolean deleteOwn(UUID accountId, String password) {
        if (!accounts.passwordMatches(accountId, password)) {
            return false;
        }
        remove(accountId);
        return true;
    }

    @Override
    public Set<UUID> adminsAmong(Collection<UUID> accountIds) {
        return accounts.adminsAmong(accountIds);
    }

    private void remove(UUID accountId) {
        transaction.executeWithoutResult(status -> {
            refuseIfLastAdmin(accountId, "This server's last admin can't be deleted. Choose another admin first.");
            if (actors.markDeleted(accountId)) {
                accounts.removeCredentials(accountId);
            }
        });
    }

    private Optional<Actor> find(UUID accountId) {
        return actors.findById(accountId).filter(account -> !account.isDeleted());
    }

    private static void refuseIfSelf(UUID accountId, Optional<UUID> actingAdmin, String message) {
        if (actingAdmin.filter(accountId::equals).isPresent()) {
            throw new AccountActionRefusedException(message);
        }
    }

    /**
     * Takes the lock until the transaction ends, so that this is still true when the change commits.
     */
    private void refuseIfLastAdmin(UUID accountId, String message) {
        accounts.lockAdmins();
        if (accounts.isLastActiveAdmin(accountId)) {
            throw new AccountActionRefusedException(message);
        }
    }
}
