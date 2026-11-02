# Elysium

Experimental ActivityPub server using Java and Spring Boot. Aim is general compatibility with Mastodon and other common
AP implementations.

## Building
You'll need JDK 25 or later installed on your system. Run the following:
```shell
./gradlew bootJar
```

Copy the resulting JAR into a suitable location.

## Running
To run:
```shell
# Copy from src/main/resources/application.properties in this repo
export SPRING_CONFIG_LOCATION=/path/to/application.properties
export SPRING_PROFILES_ACTIVE=prod
java -jar elysium.jar
```

### Configuration
Set these in your `application.properties`:

| Property                           | Description                                                                                                                                             |
|------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `elysium.instance.base-url`        | Required. Public URL of the server, with no path. A real server must use a public HTTPS URL. Actor URIs and the instance domain come from it, so don't change it after federating. |
| `elysium.bootstrap.admin-username` | Optional. Creates this local actor on startup if it doesn't exist yet.                                                                                  |
| `elysium.bootstrap.admin-password` | Optional. Sets the admin's password if it doesn't have one, and never replaces an existing one. Must be 8 to 128 characters. Prefer setting this through the `ELYSIUM_BOOTSTRAP_ADMIN_PASSWORD` environment variable. |
| `elysium.registration.enabled`     | Optional. Set to `true` to let anyone create an account at `/signup`. Defaults to `false`.                                                               |

You also need to point `spring.datasource.*` at your PostgreSQL database. Migrations run automatically on startup.

If the server is behind a reverse proxy that handles HTTPS, set `server.forward-headers-strategy=native` so that
redirects use the public URL. Only do this if the proxy is the only way to reach the server, because the server trusts
the forwarded headers it receives.

### Accounts and sign-ups
Passwords can't be changed yet, and sign-ups have no email confirmation.

Nothing limits how many login, sign-up, or app registration requests someone can make. The server limits how many
passwords it hashes at once, so a flood of requests makes logins fail with a "busy" message rather than exhausting its
memory, but it can still lock people out while it lasts. On a public server, add rate limiting at your reverse proxy,
and leave sign-ups off unless you have it.

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

API documentation is public, with Swagger UI at `/api-docs/ui` and the OpenAPI document at `/api-docs` or
`/api-docs.yaml`. These paths are set in `application.properties`, so if you maintain your own copy, make sure it
includes the `springdoc.*` settings. Without them, the documentation isn't reachable.

### Local development
`./gradlew bootRun` activates the `dev` profile, which sets the base URL to `http://localhost:8080`, and starts
PostgreSQL through `compose.yaml`. If you run the application another way, such as from your IDE, activate the `dev`
profile yourself.

### Running as a service
If you're using a Linux distribution with systemd, you can use the `elysium.service` file to start the application on
boot:
```shell
cp elysium.service /etc/systemd/system
# Optionally edit the service file to your needs...
systemctl daemon-reload
systemctl enable --now elysium.service
```