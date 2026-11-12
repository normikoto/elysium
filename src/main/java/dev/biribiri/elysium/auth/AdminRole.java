package dev.biribiri.elysium.auth;

/**
 * An SQL condition for whether the viewer is an admin, for other modules to filter what only admins see in their own
 * queries, before paging or counting. It's read each time, as the admin role is checked for requests, so that removing the
 * role applies straight away.
 *
 * <p>The condition reads the {@code :viewer} parameter, which the caller binds to a local account.
 */
public final class AdminRole {

    private AdminRole() {
    }

    public static String viewerIsAdmin() {
        return "EXISTS (SELECT 1 FROM account_credential ac WHERE ac.actor_id = :viewer AND ac.role = 'ADMIN')";
    }
}
