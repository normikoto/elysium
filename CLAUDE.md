# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Elysium is an experimental ActivityPub server (Java 25, Spring Boot 4, Spring Modulith, PostgreSQL) aiming for
compatibility with Mastodon and other common implementations. It can't fully federate yet: other servers can discover its
users, fetch actors and `Note` objects, deliver activities to its inboxes, follow and unfollow its users, which sends an
`Accept`, and receive its new and deleted statuses as `Create` and `Delete`. Other activities are ignored, and its users can't
follow anyone.

## Commands

```shell
./gradlew bootRun                                # Run locally: dev profile, starts PostgreSQL through compose.dev.yaml
./gradlew bootRun --args='create-user alice'     # Run a CLI command (also reset-password, set-role, help)
./gradlew test                                   # All tests. Most need Docker, for Testcontainers
./gradlew test --tests '*StatusesIntegrationTests'                  # One class
./gradlew test --tests '*StatusesIntegrationTests.someMethodName'   # One method
./gradlew test --tests '*ModularityTests'        # Module boundaries only, with no Docker needed
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
| `content`     | Posts and related records                  | `actor`                                        |
| `graph`       | Follows (blocks and mutes to come)         | `actor`                                        |
| `timeline`    | Home and public feeds                      | `actor`, `content`, `graph`                    |
| `api`         | Mastodon-compatible REST API               | `actor`, `auth`, `content`, `graph`, `timeline` |
| `web`         | Public HTML pages (Thymeleaf)              | `actor`, `content`                             |
| `activitypub` | Federation protocol adapter                | `actor`, `actor::signing`, `content`, `graph`, `ratelimit` |

- Only a module's base package is visible to other modules. Subpackages such as `content.internal` and `api.web` aren't.
- `actor.signing` exposes private keys as a named interface, so a module has to ask for it explicitly. Only `activitypub`
  does.
- Core modules never depend on `activitypub`, and ActivityStreams types stay inside it. The intended design is that inbound
  activities call into the core modules, and outbound activities are built from events those modules publish.
- A new module also has to be added to the list in `ModularityTests.detectsEveryModule`.

### Federation code

Only part of this is live. `InboxController` receives activities and queues them, and only `Follow` and `Undo` have an
`InboundActivityHandler`, so other types are stored and ignored. `FollowHandler` and `StatusDeliveries` use
`activitypub.delivery`, and `StatusPublished` and `StatusDeleted` are the only application events. Check `FEDERATION.md`, which only describes what's implemented today, before assuming a federation
feature is live.

- **Inbound:** `InboxController` does the HTTP and signature steps, and `Inbox` decides whether to keep an activity. Who an
  activity is for has to come from the activity, never from the inbox's URL. Checks on who's speaking for an `id` have to
  run before `InboundActivities.accept`, where the first activity stored under an ID wins. To act on a type of activity,
  add an `InboundActivityHandler`, or for another type of `Undo`, extend `UndoHandler`, since each type has one handler.
  A repeated ID is dropped unless the handler's `processesRepeats` says otherwise, since some servers reuse IDs for new requests.
  Anything a handler reads from a body that it then stores, such as an ID in an `Undo`, hasn't been through the inbox's
  checks. The inbox's only limit is on the key fetches an address can cause (`KeyFetchBudget`),
  and `SignatureVerifier.verify` needs one, so that every caller has to decide who pays for fetches. `ActivityEnvelope` only normalizes `id`, `type`, and `actor`, and `ActivityObject` normalizes `object`. The other
  properties still need the handling described in issue #39.
- **Remote actors:** `RemoteActors` fetches and stores them, and `RemoteActorDocument` decides what to believe. Remote rows
  in `actor` have no `local_account`, and can have a null `outbox_uri` and `public_key_pem`. Usernames are only unique for
  local accounts, through `actor.is_local`, since a remote actor's identity is its URI.
- **Signatures:** `SignatureVerifier`, `PublicKeyResolver`, and `SignedFetcher` are independent of the signature format.
  Each format supplies a `SignatureScheme` and a `RequestSigner`. Only draft-cavage is implemented. The reasoning for
  signer order is in `activitypub/signature/package-info.java`.
- **Outbound:** A module publishes an event inside its transaction, with only IDs in it, and a listener in
  `activitypub.outbound` builds the activity, as `StatusDeliveries` does. `StatusDeleted` also has the URI and visibility,
  which can't be looked up once the row is gone, and never has a status's text, because events are stored. Listeners have to be safe to repeat and shouldn't
  fail for what's since been deleted, because `EventRetries` runs a failed one again every 5 minutes, and an event only stays
  stored until its listeners finish. `Notes` builds `Note` objects, so that fetched and delivered ones match.
- **Outbound HTTP:** Use `activitypub.http.FederationHttpClient`, which refuses private addresses, including through its
  own DNS resolver. The lab gets around this with `elysium.federation.allowed-private-networks`, and `federation/README.md`
  rules out an on/off switch or plain HTTP.

### Jobs

Background work runs on db-scheduler, stored in PostgreSQL, with tasks built through `activitypub.jobs.Jobs`, so that they
all share the same retry and parking behavior.

- Handlers run with no transaction open and can run again after succeeding, so they have to be safe to repeat.
- `ActivityDeliveries.enqueue` requires an existing transaction, so that a change and its activity are saved together.
- Jobs have no order, so a handler can run before the job for an earlier activity. `InboundActivity`'s times say which
  arrived first, and `FollowService` explains how `undone_follow` and `undone_follow_pair` use them.
- Job data only holds IDs. The admin UI at `/admin/db-scheduler` shows it.

### Persistence

There's no ORM. Repositories use `JdbcClient` with hand-written SQL, and the schema comes from Flyway migrations in
`src/main/resources/db/migration`. These run at startup, and when CLI commands run. IDs are UUIDs, and `actor.Ids.parse` is
the strict way to read them from a request.

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
