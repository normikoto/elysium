/**
 * What every request to a server that isn't trusted needs, whichever module makes it: other servers and users choose
 * those URLs, so requests mustn't reach private addresses. It has no dependencies, so that modules which can't depend on
 * {@code activitypub}, such as {@code push}, can use it.
 */
@ApplicationModule(allowedDependencies = {})
package dev.biribiri.elysium.http;

import org.springframework.modulith.ApplicationModule;
