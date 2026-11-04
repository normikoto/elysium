/**
 * Local logins, OAuth, and HTTP security configuration.
 */
@ApplicationModule(allowedDependencies = {"actor", "ratelimit"})
package dev.biribiri.elysium.auth;

import org.springframework.modulith.ApplicationModule;
