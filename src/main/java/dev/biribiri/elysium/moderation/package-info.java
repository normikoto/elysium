/**
 * What admins use to keep this server's rules: for now, blocks on other servers. This decides what's blocked, and the
 * {@code activitypub} module enforces it, because this module knows nothing about federation.
 */
@ApplicationModule(allowedDependencies = "actor")
package dev.biribiri.elysium.moderation;

import org.springframework.modulith.ApplicationModule;
