/**
 * Background jobs on db-scheduler, stored in PostgreSQL. {@link dev.biribiri.elysium.jobs.Jobs} builds tasks that all
 * retry and park the same way. The scheduler's settings here, such as JSON job data and not running jobs during CLI
 * commands, apply to every task, including recurring ones that other modules build with db-scheduler directly.
 */
@ApplicationModule(allowedDependencies = {})
package dev.biribiri.elysium.jobs;

import org.springframework.modulith.ApplicationModule;
