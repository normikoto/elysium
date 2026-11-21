/**
 * What admins use to keep order on this server: blocks on other servers, reports, and the rules people agree to. This
 * decides what's blocked, and the {@code activitypub} module enforces it, because this module knows nothing about
 * federation.
 */
@ApplicationModule(allowedDependencies = {"actor", "content", "jobs"})
package dev.biribiri.elysium.moderation;

import org.springframework.modulith.ApplicationModule;
