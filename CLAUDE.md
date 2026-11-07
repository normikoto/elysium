# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Elysium is an experimental ActivityPub server (Java 25, Spring Boot 4, Spring Modulith, PostgreSQL) aiming for
compatibility with Mastodon and other common implementations. Federation is only partly built: `FEDERATION.md` describes
what's live today, so check it before assuming a federation feature exists.

## Commands

```shell
./gradlew bootRun                                # Run locally: dev profile, starts PostgreSQL through compose.dev.yaml
./gradlew bootRun --args='create-user alice'     # Run a CLI command (see `help` for the others)
./gradlew test                                   # All tests. Most need Docker, for Testcontainers
./gradlew test --tests '*StatusesIntegrationTests'                  # One class
./gradlew test --tests '*StatusesIntegrationTests.someMethodName'   # One method
./gradlew test --tests '*ModularityTests'        # Module boundaries only, with no Docker needed
./gradlew loadTest                               # Measures delivery under load, and prints the numbers. Takes minutes
./gradlew bootJar                                # Builds build/libs/elysium.jar
./gradlew bootBuildImage                         # Builds elysium:latest. -PbindDockerHostToBuilder=false for Docker Desktop or Colima
federation/smoke.sh gotosocial                   # Or mastodon, or misskey. Needs Docker, jq, and a fresh bootBuildImage
```

No linter or formatter is configured. CI runs `./gradlew test`, and the federation smoke tests. See
`federation/README.md` for the lab and for debugging its failures.

If you run the application outside Gradle, such as from an IDE, activate the `dev` profile yourself.

## Architecture

### Modules

Each top-level package under `dev.biribiri.elysium` is a Spring Modulith module, and `ModularityTests` enforces the
boundaries. Allowed dependencies are declared in each module's `package-info.java`:

| Module        | Purpose                                    | May depend on                                  |
|---------------|--------------------------------------------|------------------------------------------------|
| `actor`       | Local and remote actors, profiles, keys    | nothing                                        |
| `ratelimit`   | Counting attempts per client               | `actor`                                        |
| `auth`        | Logins, OAuth, HTTP security configuration | `actor`, `ratelimit`                           |
| `content`     | Posts and related records                  | `actor`, `graph`                               |
| `graph`       | Follows (blocks and mutes to come)         | `actor`                                        |
| `moderation`  | Domain blocks                              | `actor`                                        |
| `timeline`    | Home and public feeds                      | `actor`, `content`, `graph`                    |
| `api`         | Mastodon-compatible REST API               | `actor`, `auth`, `content`, `graph`, `moderation`, `timeline` |
| `web`         | Public HTML pages (Thymeleaf)              | `actor`, `content`                             |
| `activitypub` | Federation protocol adapter                | `actor`, `actor::signing`, `content`, `graph`, `moderation`, `ratelimit` |

- Only a module's base package is visible to other modules. Subpackages such as `content.internal` and `api.web` aren't.
- `actor.signing` exposes private keys as a named interface, so a module has to ask for it explicitly. Only `activitypub`
  does.
- Core modules never depend on `activitypub`, and ActivityStreams types stay inside it. The intended design is that inbound
  activities call into the core modules, and outbound activities are built from events those modules publish.
- A new module also has to be added to the list in `ModularityTests.detectsEveryModule`.

### Federation rules

`activitypub`'s classes document how they work. These are the rules that a change can break without a test noticing.

- **Inbound:** who an activity is for comes from the activity, never from the inbox's URL. Checks on who's speaking for an
  `id` run before `InboundActivities.accept`, where the first activity stored under an ID wins. Each activity type has one
  `InboundActivityHandler`, and another type of `Undo` extends `UndoHandler`. Anything a handler reads from a body and then
  stores hasn't been through the inbox's checks, and a handler has to check any actor or object it fetches or stores other
  than the activity's actor. Recipients come from `LocalRecipients`, and parsing goes through `ActivityJson` and the parsers
  that use it.
- **Signatures:** `SignatureVerifier.verify` needs a `KeyFetchBudget`, so every caller decides who pays for key fetches.
  Signer order is explained in `activitypub/signature/package-info.java`.
- **Outbound:** a module publishes an event inside its transaction, holding only IDs and never a status's text, since events
  are stored, and a listener in `activitypub.outbound` builds the activity. Listeners have to be safe to repeat and mustn't fail
  for what's since been deleted, because `EventRetries` reruns failed ones. Build `Note`s with `Notes`, so fetched and delivered
  ones match.
- **Remote actors:** other modules find them through `actor.RemoteActorResolver`, never by depending on `activitypub`.
- **Account state:** `ActorService.findLocal*` and `StatusRepository`'s queries only find active actors. Use `findAnyLocal*` only
  for what has to keep working for suspended or deleted ones, such as actor documents and signing, and check the state of
  anything from `findByUri` or `findById`. `auth.AccountActions` decides who can be suspended or deleted.
- **Cleanup:** new remote data has to be removed by `DomainBlockCleanupTask`, and new remote or account data by
  `AccountCleanupTask`. Domain blocks are decided in `moderation.DomainBlocks` and enforced in `activitypub`.
- **Outbound HTTP:** use `activitypub.http.FederationHttpClient`, which refuses private addresses. The lab uses
  `elysium.federation.allowed-private-networks`, and `federation/README.md` rules out an on/off switch or plain HTTP.

### Jobs

Background work runs on db-scheduler, stored in PostgreSQL, with tasks built through `activitypub.jobs.Jobs`, so that they
all share the same retry and parking behavior.

- Handlers run with no transaction open and can run again after succeeding, so they have to be safe to repeat.
- `ActivityDeliveries.enqueue` requires an existing transaction, so that a change and its activity are saved together.
- Jobs have no order, so a handler can run before the job for an earlier activity. `FollowService` shows how to cope.
- Job data only holds IDs. The admin UI at `/admin/db-scheduler` shows it.
- Rerun `./gradlew loadTest` after changing how jobs wait, such as `HostGate` or `activitypub.http.HostRequests`.

### Persistence

There's no ORM. Repositories use `JdbcClient` with hand-written SQL, and the schema comes from Flyway migrations in
`src/main/resources/db/migration`. These run at startup, and when CLI commands run. IDs are UUIDs, and `actor.Ids.parse` is
the strict way to read them from a request. Read queries with `.list()` or `.optional()`, not `.stream()`, which
holds a connection until it's closed. `DatabaseStreamsTests` enforces this.

### CLI commands

`ElysiumApplication.main` treats any argument that isn't an option as a command, and hands it to `CommandRunner`. To add
one, write a picocli `@Command` class anywhere, implementing `Callable<Integer>`, with a no-argument constructor and
`@Autowired` methods for its dependencies. The command line is parsed before Spring starts. Commands run with no web
server and no job worker, so they're safe to run alongside the server.

### Configuration

`src/main/resources/application.properties` holds defaults built into the JAR, and deployments only override what they
change. New settings an operator would use also belong in `application.properties.example`, `docs/configuration.md`,
`.env.example`, and the `environment` section of `compose.yaml`, which passes them to the container.

`elysium.instance.base-url` is required, and actor URIs are derived from it.

## Testing

- Integration tests are `@SpringBootTest` with `@Import(TestcontainersConfiguration.class)`, which runs a real PostgreSQL.
- `src/test/resources/config/application.properties` turns the login and sign-up rate limits off, because test classes
  share contexts and one address. `RateLimitIntegrationTests` turns them back on. The inbox's limit on key fetches is always on, so
  tests that make an inbox fetch keys should set their own address, as `InboxIntegrationTests` does.
- The sign-up page refuses to create the first account, and a test class's database can start empty. Call
  `TestAccounts.makeSureAnAccountExists` before a test signs up.

## Documentation

User-facing changes go in the `[Unreleased]` section of `CHANGELOG.md`, with upgrade steps when an operator has to do
something. Federation behavior goes in `FEDERATION.md`, and API differences from Mastodon in `docs/api.md`.

`README.md`, `CHANGELOG.md`, and `docs/` other than `docs/api.md` are for instance admins setting up and running the server. Only
write what an admin needs to install, configure, operate, or understand the server's visible behavior: settings, commands,
what happens to their users and data, limits they may hit, and security pitfalls they have to avoid. Leave out how it's
built, such as class names, tables, job internals, the order things happen in, exact retention times, and edge cases an
admin won't notice. Those belong in code comments, `package-info.java`, or this file. Keep each fact short, and put it in
one place, linking to it from elsewhere. `federation/README.md` and `docs/federation-plan.md` are for contributors, and are
the exception.

`FEDERATION.md` follows FEP-67ff, so it's for developers of other fediverse servers as well as admins. Describe what another
server can observe and has to know to interoperate: endpoints, content types, response codes, which activities are sent and
accepted and what they look like, how they're addressed, IDs, signature requirements, limits, ordering and repeat behavior,
and differences from common implementations. Leave out internals that can't be seen from outside, such as class names,
tables, or how jobs are run, and link to `docs/` for admin steps rather than repeating them.

`docs/api.md` is for developers of client applications. List every difference from Mastodon's API that a client could run
into: missing endpoints, parameters, and fields, different defaults, limits, validation that refuses what Mastodon accepts,
and different error responses. Leave out how the server implements them.
