---
paths:
  - "src/main/java/dev/biribiri/elysium/activitypub/**"
  - "src/main/java/dev/biribiri/elysium/jobs/**"
  - "src/main/java/dev/biribiri/elysium/**/*Task.java"
  - "src/main/java/dev/biribiri/elysium/graph/FollowService.java"
---

# Background jobs

Background work runs on db-scheduler, stored in PostgreSQL, with tasks built through `jobs.Jobs`, so that they all
share the same retry and parking behavior. Recurring tasks use db-scheduler's `Tasks.recurring` directly.

- A class that queues its own jobs declares their task as a static `@Bean`, as `Jobs.task` explains.
- Handlers run with no transaction open and can run again after succeeding, so they have to be safe to repeat.
- `ActivityDeliveries.enqueue` requires an existing transaction, so that a change and its activity are saved together.
- Jobs have no order, so a handler can run before the job for an earlier activity. `FollowService` shows how to cope.
- Job data only holds IDs. The admin UI at `/admin/db-scheduler` shows it.
- Rerun `./gradlew loadTest` after changing how jobs wait, such as `HostGate` or `activitypub.http.HostRequests`.
