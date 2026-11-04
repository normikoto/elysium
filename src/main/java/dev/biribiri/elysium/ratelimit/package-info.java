/**
 * Counting attempts per client, for any module that has to limit them.
 */
@ApplicationModule(allowedDependencies = "actor")
package dev.biribiri.elysium.ratelimit;

import org.springframework.modulith.ApplicationModule;
