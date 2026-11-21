/**
 * Relationships between actors: follows, blocks, mutes, private notes, and the lists that users sort the accounts they
 * follow into.
 */
@ApplicationModule(allowedDependencies = "actor")
package dev.biribiri.elysium.graph;

import org.springframework.modulith.ApplicationModule;
