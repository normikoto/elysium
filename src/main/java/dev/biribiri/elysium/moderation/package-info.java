/**
 * What admins use to keep this server's rules: blocks on other servers, and reports. This decides what's blocked, and the
 * {@code activitypub} module enforces it, because this module knows nothing about federation.
 */
@ApplicationModule(allowedDependencies = {"actor", "content"})
package dev.biribiri.elysium.moderation;

import org.springframework.modulith.ApplicationModule;
