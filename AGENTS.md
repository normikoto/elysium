# AGENTS.md

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
./gradlew loadTest                               # Measures delivery, and removing old remote statuses, under load. Takes minutes
./gradlew bootJar                                # Builds build/libs/elysium.jar
./gradlew bootBuildImage                         # Builds elysium:latest. -PbindDockerHostToBuilder=false for Docker Desktop or Colima
federation/smoke.sh gotosocial                   # Or mastodon, or misskey. Needs Docker, jq, openssl, and a fresh bootBuildImage
```

No linter or formatter is configured. CI runs `./gradlew test`, and the federation smoke tests. See
`federation/README.md` for the lab and for debugging its failures.

Publishing a GitHub release runs `.github/workflows/release.yml`, which attaches the JAR and config files to the release
and pushes the images to `ghcr.io`. It fails unless `version` in `build.gradle.kts` matches the tag, so the release pull
request sets it before tagging.

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
| `jobs`        | Background jobs, and retrying event listeners | nothing                                     |
| `http`        | Requests to untrusted servers that can't reach private addresses | nothing                     |
| `actor`       | Local and remote actors, profiles, keys    | `jobs`, `media`, `text`                        |
| `ratelimit`   | Counting attempts per client               | `actor`                                        |
| `auth`        | Logins, sign-ups, OAuth, HTTP security     | `actor`, `mail`, `moderation`, `ratelimit`     |
| `content`     | Posts and related records, and attachments | `actor`, `graph`, `media`, `text`              |
| `graph`       | Follows, blocks, mutes, and lists, and their CSV files | `actor`, `jobs`                    |
| `moderation`  | Domain blocks, reports, and server rules   | `actor`, `content`, `jobs`                     |
| `announcement` | Admins' announcements, and their dismissals and reactions | `actor`, `content`              |
| `timeline`    | Home, list, and public feeds, and read markers | `actor`, `content`, `graph`                    |
| `notification` | Notifications and direct-message conversations | `actor`, `auth`, `content`, `graph`, `moderation`, `timeline` |
| `push`        | Web Push subscriptions, and sending notifications to push services | `actor`, `auth`, `content`, `graph`, `http`, `jobs`, `notification`, `text` |
| `api`         | Mastodon-compatible REST API               | `actor`, `announcement`, `auth`, `content`, `graph`, `moderation`, `notification`, `push`, `text`, `timeline` |
| `web`         | Public HTML pages (Thymeleaf)              | `actor`, `auth`, `content`, `graph`, `moderation`, `ratelimit`, `text`, `timeline` |
| `activitypub` | Federation protocol adapter                | `actor`, `actor::signing`, `content`, `graph`, `http`, `jobs`, `moderation`, `ratelimit`, `text` |

- Only a module's base package is visible to other modules. Subpackages such as `content.internal` and `api.web` aren't.
- `actor.signing` exposes private keys as a named interface, so a module has to ask for it explicitly. Only `activitypub`
  does.
- Core modules never depend on `activitypub`, and ActivityStreams types stay inside it. The intended design is that inbound
  activities call into the core modules, and outbound activities are built from events those modules publish.
- A new module also has to be added to the list in `ModularityTests.detectsEveryModule`.
- Outside `activitypub`, don't name a module, class, or table with a word that ActivityPub uses for something else, such
  as inbox, outbox, activity, or collection. `activitypub` uses both vocabularies, so a clash there reads as federation.
  `actor` is the exception, since it means the same thing in both. So is `announcement`, Mastodon's name for admins'
  announcements, which never federate and so can't be mistaken for an `Announce`.
- Name a class for what it does with its records or events, as `FollowDeliveries` and `Notifier` are, not for a pattern
  such as a projection or a manager.

### Cross-module rules

These reach beyond `activitypub`, and a change can break them without a test noticing.

- **Outbound events:** a module publishes an event inside its transaction, holding IDs, and never a status's text, since
  events are stored. An event about a record that's been deleted also holds what its listener needs to undo it, as
  `StatusUnpinned` and `TagUnfeatured` do. `activitypub.outbound` builds the activity from it.
- **Remote actors:** other modules find them through `actor.RemoteActorResolver`, never by depending on `activitypub`.
- **Account state:** `ActorService.findLocal*`, and status queries built on `content.StatusSql.FROM_ACTIVE`, only find
  active actors. Use `findAnyLocal*` only for what has to keep working for suspended or deleted ones, such as actor
  documents and signing, and check the state of anything from `findByUri` or `findById`. `auth.AccountActions` decides
  who can be suspended or deleted.
- **Cleanup:** each module that stores data by or about actors implements `actor.ActorDataRemoval` once, and a new
  table's data has to be removed there: remote data when its server is blocked, and remote or account data when its
  actor is deleted, including files in the media directory. The account and domain block cleanup jobs call every
  implementation.
- **Domain blocks:** a block is a suspension or only limits a server, so `DomainBlocks.isBlocked` means suspended. SQL
  outside `moderation` reads `domain_block` only through `actor.ActorSql`, which says which blocks count.

### Persistence

There's no ORM. Repositories use `JdbcClient` with hand-written SQL, and the schema comes from Flyway migrations in
`src/main/resources/db/migration`. These run at startup, and when CLI commands run.

- IDs are UUIDs, and `actor.Ids.parse` is the strict way to read them from a request.
- A rule that other modules' SQL has to agree with is exported once as SQL conditions, and used rather than written
  again: `actor.ActorSql` for active actors, blocked servers, and username searches, `graph.HiddenAccounts` for blocks and mutes, and
  `content.StatusSql` for which statuses a viewer can see.
- A list paged by Mastodon's ID cursors takes an `actor.IdPage`, whose `cursors` and `orderAndLimit` write its SQL.
  How many a page can hold is up to each API, in `api.web.PageLimits`.
- Read queries with `.list()` or `.optional()`, not `.stream()`, which holds a connection until it's closed.
  `DatabaseStreamsTests` enforces this.

### CLI commands

`ElysiumApplication.main` treats any argument that isn't an option as a command, and hands it to `CommandRunner`.

- To add one, write a picocli `@Command` class anywhere, implementing `Callable<Integer>`, with a no-argument
  constructor and `@Autowired` methods for its dependencies.
- The command line is parsed before Spring starts.
- Commands run with no web server and no job worker, so they're safe to run alongside the server.

### Configuration

- `src/main/resources/application.properties` holds defaults built into the JAR, and deployments only override what
  they change.
- New settings an operator would use also belong in `application.properties.example`, `docs/configuration.md`,
  `.env.example`, and the `environment` section of `compose.yaml`, which passes them to the container.
- `elysium.instance.base-url` is required, and actor URIs are derived from it.

### Area rules

`.claude/rules/` holds the guidance for each area, and each file loads when you work on the files it covers. Read the one
that applies before changing an area from somewhere it doesn't load:

| File                | Covers                                                          |
|---------------------|-----------------------------------------------------------------|
| `federation.md`     | Inbound and outbound activities, signatures, outbound HTTP      |
| `jobs.md`           | db-scheduler tasks, retries, ordering, delivery                 |
| `openapi.md`        | Annotating client API and federation endpoints                  |
| `documentation.md`  | Each document's audience, and how to write them                 |

## Testing

- Integration tests are `@SpringBootTest` with `@Import(TestcontainersConfiguration.class)`, which runs a real
  PostgreSQL. One container serves the whole run, and each Spring context gets an empty database of its own in it.
- `src/test/resources/config/application.properties` turns the login and sign-up rate limits off, because test classes
  share contexts and one address. `RateLimitIntegrationTests` turns them back on.
- The inbox's limit on key fetches is always on, so tests that make an inbox fetch keys should set their own address, as
  `InboxIntegrationTests` does.
- The sign-up page refuses to create the first account, and a test class's database can start empty. Call
  `TestAccounts.makeSureAnAccountExists` before a test signs up.

## Code comments

Comments and `package-info.java` explain _why_ the code does what it does: the constraint it works within, the failure
it prevents, the decision behind it, or what would break if it changed.

- Don't restate or overdescribe what the code does, narrate it step by step, or repeat what names and types already say.
- If a comment would only paraphrase the code beneath it, leave it out.

## Documentation

A user-facing change goes in `CHANGELOG.md`'s `[Unreleased]` section, with upgrade steps when an operator has to do
something. A change to what clients or other servers can observe also updates `docs/api.md` or `FEDERATION.md`, and the
endpoint's OpenAPI annotations. `.claude/rules/documentation.md` says what belongs in each document and how to write it.
