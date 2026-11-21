/**
 * Announcements that admins write for the people on this server, and those people's dismissals and emoji reactions,
 * which Mastodon clients show above the home timeline. They don't federate, since Mastodon only shows a server's
 * announcements to its own accounts, so only local accounts dismiss or react to them.
 */
@ApplicationModule(allowedDependencies = {"actor", "content"})
package dev.biribiri.elysium.announcement;

import org.springframework.modulith.ApplicationModule;
