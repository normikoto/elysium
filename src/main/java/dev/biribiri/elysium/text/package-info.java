/**
 * Turns the plain text that people write into HTML, and makes HTML from other servers safe to show. Statuses and profiles
 * both use it, so that their links look and count the same.
 */
@ApplicationModule(allowedDependencies = {})
package dev.biribiri.elysium.text;

import org.springframework.modulith.ApplicationModule;
