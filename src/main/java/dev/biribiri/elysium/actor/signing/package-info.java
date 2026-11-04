/**
 * Access to local actors' private keys. This is its own interface so that a module has to ask for it by name in its
 * allowed dependencies. Only the {@code activitypub} module does.
 */
@NamedInterface("signing")
package dev.biribiri.elysium.actor.signing;

import org.springframework.modulith.NamedInterface;
