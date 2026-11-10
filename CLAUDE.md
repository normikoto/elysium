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
| `media`       | Uploaded files, kept on disk               | nothing                                        |
| `mail`        | Sending email over SMTP                    | nothing                                        |
| `text`        | Rendering plain text as HTML               | nothing                                        |
| `actor`       | Local and remote actors, profiles, keys    | `media`, `text`                                |
| `ratelimit`   | Counting attempts per client               | `actor`                                        |
| `auth`        | Logins, sign-ups, OAuth, HTTP security     | `actor`, `mail`, `ratelimit`                   |
| `content`     | Posts and related records, and attachments | `actor`, `graph`, `media`, `text`              |
| `graph`       | Follows (blocks and mutes to come)         | `actor`                                        |
| `moderation`  | Domain blocks                              | `actor`                                        |
| `inbox`       | Notifications, conversations, read markers | `actor`, `content`, `graph`, `moderation`        |
| `timeline`    | Home and public feeds                      | `actor`, `content`, `graph`                    |
| `api`         | Mastodon-compatible REST API               | `actor`, `auth`, `content`, `graph`, `inbox`, `moderation`, `text`, `timeline` |
| `web`         | Public HTML pages (Thymeleaf)              | `actor`, `content`, `text`                     |
| `activitypub` | Federation protocol adapter                | `actor`, `actor::signing`, `content`, `graph`, `inbox`, `moderation`, `ratelimit`, `text` |

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
  `AccountCleanupTask`, including files in the media directory. Domain blocks are decided in `moderation.DomainBlocks` and
  enforced in `activitypub`. A block is a suspension or only limits a server, so `DomainBlocks.isBlocked` means suspended,
  and SQL that reads `domain_block` has to filter on `severity`.
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

Each document has one audience. Keep each fact short, put it in one place, and link to it from elsewhere. Describe only
what its readers can observe or have to do, and leave out how the server is built: class names, tables, job internals,
the order things happen in inside the server, exact retention times, and edge cases its readers won't notice.
`federation/README.md` and `docs/federation-plan.md` are the exception, since they're for contributors.

- **`README.md`, `CHANGELOG.md`, and `docs/` other than `docs/api.md`** are for instance admins. Write only what an admin
  needs to install, configure, operate, or understand the server's visible behavior: settings, commands, what happens to
  their users and data, limits they may hit, and security pitfalls to avoid.
- **`CHANGELOG.md`** gets every user-facing change in its `[Unreleased]` section, with upgrade steps when an operator has
  to do something.
- **`FEDERATION.md`** follows FEP-67ff, so it's for developers of other fediverse servers as well as admins. Describe what
  another server can observe and has to know to interoperate: endpoints, content types, response codes, which activities
  are sent and accepted and what they look like, how they're addressed, IDs, signature requirements, limits, ordering and
  repeat behavior, and differences from common implementations. Link to `docs/` for admin steps rather than repeating
  them.
- **`docs/api.md`** is for developers of client applications. List every difference from Mastodon's API that a client
  could run into: missing endpoints, parameters, and fields, different defaults, limits, validation that refuses what
  Mastodon accepts, and different error responses.

### Code comments

Comments and `package-info.java` explain _why_ the code does what it does: the constraint it works within, the failure
it prevents, the decision behind it, or what would break if it changed. Don't restate or overdescribe what the code does,
narrate it step by step, or repeat what names and types already say. If a comment would only paraphrase the code beneath
it, leave it out.

### OpenAPI

The OpenAPI document repeats `docs/api.md` and `FEDERATION.md` for each endpoint, so a change to an endpoint's behavior
also changes its annotations. `ApiDocsIntegrationTests` checks that every endpoint is described.

- Client API endpoints have an `@Operation` with a summary, a link to Mastodon's documentation, the differences from
  `docs/api.md` that apply to it, and its error responses.
- `api.web.ApiDocs` fills in what they have in common: the `Error` body for every error, the 401 and 403 that an
  endpoint's scopes imply, form and multipart bodies like the JSON one, and the paging cursors and `Link` header for a
  list that takes `max_id`.
- Request bodies that are read by hand are documented with a private record in the controller, as JSON only. Where a form
  and a JSON mapping share a path, springdoc combines them and uses the JSON one's annotations.
- Federation endpoints follow `FEDERATION.md`. `activitypub.web.FederationApiDocs` only keeps a body on a response whose
  annotation declares one, documents each `application/activity+json` body as `application/ld+json` too, and describes
  the request every inbox takes. `InboxController`'s class-level `@ApiResponse`s are what every inbox answers.
