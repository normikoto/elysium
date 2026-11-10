/**
 * Posts, likes, boosts, mentions, and media.
 */
@ApplicationModule(allowedDependencies = {"actor", "graph", "media", "text"})
package dev.biribiri.elysium.content;

import org.springframework.modulith.ApplicationModule;
