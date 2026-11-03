# Elysium

Experimental ActivityPub server using Java and Spring Boot. Aim is general compatibility with Mastodon and other common
AP implementations.

**There are known limitations and pitfalls when it comes to being production-ready right now, please don't run this on a
public server unless you really know what you're doing.**

## Building
You'll need JDK 25 or later installed on your system. Run the following:
```shell
./gradlew bootJar
```

Copy the resulting `build/libs/elysium.jar` into a suitable location.

## Running
To run:
```shell
# Copy from application.properties.example in this repo
export SPRING_CONFIG_ADDITIONAL_LOCATION=/path/to/application.properties
export SPRING_PROFILES_ACTIVE=prod
java -jar elysium.jar
```

### Running with Docker Compose
`compose.yaml` runs the server and a PostgreSQL database. You'll need Docker, as well as JDK 25 or later to build the
image:
```shell
./gradlew bootBuildImage
cp .env.example .env
# Edit .env to set your server's URL and a database password...
docker compose up -d
```

If the image build can't connect to Docker, which can happen with Docker Desktop or Colima, build it with
`./gradlew bootBuildImage -PbindDockerHostToBuilder=false`.

PostgreSQL isn't reachable from outside its Docker network, and its data is kept in the `postgres-data` volume. The
server listens on `127.0.0.1:8080`, so you need a reverse proxy on the same host to handle HTTPS. Because the proxy is
the only way to reach the server, `server.forward-headers-strategy=native` is already set.

To upgrade, run `./gradlew bootBuildImage` again, then `docker compose up -d`.

### Configuration
Your `application.properties` only needs the settings you want to change. Anything else uses the defaults built into the
JAR, so upgrades can update those defaults without you editing your file. If you use `SPRING_CONFIG_LOCATION` instead,
those defaults aren't loaded, and parts of the server won't work as documented.

Set these in your `application.properties`:

| Property                           | Description                                                                                                                                             |
|------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `elysium.instance.base-url`        | Required. Public URL of the server, with no path. A real server must use a public HTTPS URL. Actor URIs and the instance domain come from it, so don't change it after federating. |
| `elysium.bootstrap.admin-username` | Optional. Creates this local actor on startup if it doesn't exist yet.                                                                                  |
| `elysium.bootstrap.admin-password` | Optional. Sets the admin's password if it doesn't have one, and never replaces an existing one. Must be 8 to 128 characters. Prefer setting this through the `ELYSIUM_BOOTSTRAP_ADMIN_PASSWORD` environment variable. |
| `elysium.registration.enabled`     | Optional. Set to `true` to let anyone create an account at `/signup`. Defaults to `false`.                                                               |
| `elysium.statuses.max-characters`  | Optional. The most characters a new status can have, counting its content warning. Each URL counts as 23 characters. Defaults to `5000`. Lowering it doesn't affect existing statuses. |

You also need to point `spring.datasource.*` at your PostgreSQL database. Migrations run automatically on startup.

If the server is behind a reverse proxy that handles HTTPS, set `server.forward-headers-strategy=native` so that
redirects use the public URL. Only do this if the proxy is the only way to reach the server, because the server trusts
the forwarded headers it receives.

### Accounts and sign-ups
Sign-ups are currently disabled by default.

Passwords can't be changed yet, and sign-ups have no email confirmation.

**Warning:**  The server has no rate limiting for logins or registrations, aside from a limit to how many passwords the
server hashes at one time. For this reason, it's _strongly recommended_ to not use this software on a public facing
server at this time. If you're daring, you can add rate limiting to your reverse proxy configuration to mitigate
this problem.

Login sessions are stored in memory, so restarting logs people out of the login page. Applications' access tokens are
stored in the database and aren't affected. If you run more than one instance, your load balancer needs to keep each
browser on the same instance.

## Using the API
Applications authenticate with OAuth 2, following
[Mastodon's OAuth API](https://docs.joinmastodon.org/methods/oauth/): `POST /api/v1/apps`, `GET /oauth/authorize`,
`POST /oauth/token`, and `POST /oauth/revoke`, with discovery at `/.well-known/oauth-authorization-server`. Elysium
differs from Mastodon in these ways:

- `force_login`, `prompt`, `lang`, `/oauth/userinfo`, and `POST /api/v1/accounts` aren't implemented.
- Each authorization issues a new token rather than reusing the application's existing one.
- Revoking an unknown token succeeds, rather than returning the 403 that Mastodon documents.
- `/oauth/token` and `/oauth/revoke` only accept form-encoded bodies, not JSON.

Statuses follow [Mastodon's statuses API](https://docs.joinmastodon.org/methods/statuses/), with these differences:

- Statuses can't be edited, and media, polls, scheduled posts, and `GET /api/v1/statuses/:id/context` aren't implemented.
- The length limit defaults to 5000 characters rather than 500. As in Mastodon, each URL counts as 23 characters, unless
  it's longer than 2048 characters, in which case it counts at its full length. URLs can save at most 4096 characters
  per status, and any URLs after that also count at their full length.
- `visibility` defaults to `public` only when it's missing. An empty value is rejected, as are a repeated value and
  `visibility[]`.
- `GET /api/v1/instance` reports the limit in `max_toot_chars` and `configuration.statuses.max_characters`, but
  `/api/v2/instance` isn't implemented. Clients that don't read the limit from v1 may apply their own limit of 500.
- `Idempotency-Key` is accepted but ignored, so a client that retries a request can create a duplicate status.

API documentation is public, with Swagger UI at `/api-docs/ui` and the OpenAPI document at `/api-docs` or
`/api-docs.yaml`.

### Local development
`./gradlew bootRun` activates the `dev` profile, which sets the base URL to `http://localhost:8080`, and starts
PostgreSQL through `compose.dev.yaml`. If you run the application another way, such as from your IDE, activate the `dev`
profile yourself.

### Running as a service
If you're using a Linux distribution with systemd, you can use the `elysium.service` file to start the application on
boot. It runs the server as a dedicated `elysium` user, in a sandbox. As root:
```shell
# Create the user and group
useradd --system --user-group --home-dir /opt/elysium --shell /usr/sbin/nologin elysium

# Install the JAR. The server only needs to read it, so root keeps ownership.
install -d -m 755 /opt/elysium
install -m 644 build/libs/elysium.jar /opt/elysium/elysium.jar

# Install your config, which only the elysium group can read because it contains your database password
install -d -m 750 -g elysium /etc/elysium
install -m 640 -g elysium application.properties.example /etc/elysium/application.properties
# Edit /etc/elysium/application.properties...

cp elysium.service /etc/systemd/system
systemctl daemon-reload
systemctl enable --now elysium.service
```

To change a setting in the service file, use `systemctl edit elysium` instead of editing the file, so that installing a
newer version of it doesn't lose your change.

To set the admin's password through `ELYSIUM_BOOTSTRAP_ADMIN_PASSWORD`, put it in `/etc/elysium/elysium.env` before
starting the service. systemd reads this file as root, so the server's user doesn't need access:
```shell
install -m 600 /dev/null /etc/elysium/elysium.env
# Add a line to /etc/elysium/elysium.env: ELYSIUM_BOOTSTRAP_ADMIN_PASSWORD=...
```

The sandbox makes the filesystem read-only apart from private `/tmp` and `/var/tmp` directories and `/dev/shm`, which
isn't private. It also hides `/home`, `/root` and `/run/user`, so `java` and the JAR must be installed outside them. If
you need the server to write somewhere, or to listen on a port below 1024, the comments in `elysium.service` explain what
to change.
