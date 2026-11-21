/**
 * The client-facing REST API, compatible with Mastodon clients.
 */
@ApplicationModule(allowedDependencies = {"actor", "announcement", "auth", "content", "graph", "moderation", "notification", "text", "timeline"})
package dev.biribiri.elysium.api;

import org.springframework.modulith.ApplicationModule;
