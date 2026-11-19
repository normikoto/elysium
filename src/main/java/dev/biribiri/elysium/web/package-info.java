/**
 * The public HTML pages: the front page, profiles, and statuses, and following from them.
 */
@ApplicationModule(allowedDependencies = {"actor", "auth", "content", "graph", "ratelimit", "text"})
package dev.biribiri.elysium.web;

import org.springframework.modulith.ApplicationModule;
