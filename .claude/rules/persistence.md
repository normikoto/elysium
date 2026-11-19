---
paths:
  - "src/main/java/dev/biribiri/elysium/**/internal/**"
  - "src/main/java/dev/biribiri/elysium/activitypub/**"
  - "src/main/resources/db/migration/**"
---

# Persistence

There's no ORM. Repositories use `JdbcClient` with hand-written SQL, and the schema comes from Flyway migrations in
`src/main/resources/db/migration`. These run at startup, and when CLI commands run.

- IDs are UUIDs, and `actor.Ids.parse` is the strict way to read them from a request.
- Read queries with `.list()` or `.optional()`, not `.stream()`, which holds a connection until it's closed.
  `DatabaseStreamsTests` enforces this.
- A domain block is a suspension or only limits a server, so SQL that reads `domain_block` has to filter on `severity`.
- A new table of remote or account data needs cleanup. See Cross-module rules in `AGENTS.md`.
