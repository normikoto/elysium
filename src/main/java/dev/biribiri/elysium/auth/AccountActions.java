package dev.biribiri.elysium.auth;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Suspends and deletes accounts, for admins and commands. This decides who's allowed, apart from checking that whoever asked
 * is an admin, which is up to the caller.
 */
public interface AccountActions {

    /**
     * Suspends a local or remote account, which keeps what's stored about it so that this can be undone. Doing this to an
     * account that's already suspended changes nothing.
     *
     * @param actingAdmin who's asking, or empty for a command, which has nobody to protect from themselves
     * @return whether the account exists
     * @throws AccountActionRefusedException if it would suspend who's asking, or the server's last admin
     */
    boolean suspend(UUID accountId, Optional<UUID> actingAdmin);

    /**
     * Ends an account's suspension.
     *
     * @return whether the account exists
     * @throws AccountActionRefusedException if the account isn't suspended
     */
    boolean unsuspend(UUID accountId);

    /**
     * Permanently deletes a local account. Its username stays taken, because other servers can still know its URI.
     *
     * @param actingAdmin who's asking, or empty for a command, which has nobody to protect from themselves
     * @return whether the account exists
     * @throws AccountActionRefusedException if the account is remote, or it would delete who's asking, or the server's last
     *                                       admin
     */
    boolean delete(UUID accountId, Optional<UUID> actingAdmin);

    /**
     * @return which of these accounts have the admin role
     */
    Set<UUID> adminsAmong(Collection<UUID> accountIds);

    /**
     * @return the email addresses of those of these accounts that have one, which only accounts that signed up without an
     * invite do
     */
    Map<UUID, String> emailsOf(Collection<UUID> accountIds);
}
