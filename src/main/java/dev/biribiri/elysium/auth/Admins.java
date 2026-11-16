package dev.biribiri.elysium.auth;

import java.util.UUID;

/**
 * Whether an account is an admin, for pages in other modules that link to the admin pages. It's read each time, as the
 * admin pages check the role for each request, so that a link isn't shown to someone who's lost access.
 */
public interface Admins {

    boolean isAdmin(UUID accountId);
}
