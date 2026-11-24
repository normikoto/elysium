/**
 * Web Push: sending notifications to the push services that clients subscribe through, so that a phone hears about
 * them while its app isn't running.
 */
@ApplicationModule(allowedDependencies = {"actor", "auth", "content", "graph", "http", "jobs", "notification", "text"})
package dev.biribiri.elysium.push;

import org.springframework.modulith.ApplicationModule;
