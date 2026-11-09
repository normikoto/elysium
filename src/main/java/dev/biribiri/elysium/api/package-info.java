/**
 * The client-facing REST API, compatible with Mastodon clients.
 */
@ApplicationModule(allowedDependencies = {"actor", "auth", "content", "graph", "inbox", "moderation", "text", "timeline"})
package dev.biribiri.elysium.api;

import org.springframework.modulith.ApplicationModule;
