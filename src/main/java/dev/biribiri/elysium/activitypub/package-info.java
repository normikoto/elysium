/**
 * The ActivityPub protocol adapter. ActivityStreams types stay inside this module, and the core modules never depend on
 * it: inbound activities call into them, and outbound activities are built from the events they publish.
 */
@ApplicationModule(allowedDependencies = {"actor", "content", "graph"})
package dev.biribiri.elysium.activitypub;

import org.springframework.modulith.ApplicationModule;
