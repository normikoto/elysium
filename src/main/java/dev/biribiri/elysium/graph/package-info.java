/**
 * Relationships between actors: follows, blocks, mutes, private memos, and the lists that users sort the accounts they
 * follow into. Local accounts' follows of hashtags are here too.
 */
@ApplicationModule(allowedDependencies = {"actor", "jobs"})
package dev.biribiri.elysium.graph;

import org.springframework.modulith.ApplicationModule;
