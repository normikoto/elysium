/** Notifications, direct-message conversations, and client read positions. */
@ApplicationModule(allowedDependencies = {"actor", "auth", "content", "graph", "moderation"})
package dev.biribiri.elysium.inbox;

import org.springframework.modulith.ApplicationModule;
