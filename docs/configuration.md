# Configuration

Your `application.properties` only needs the settings you want to change. Anything else uses the defaults built into the
JAR, so upgrades can update those defaults without you editing your file. If you use `SPRING_CONFIG_LOCATION` instead,
those defaults aren't loaded, and parts of the server won't work as documented.

Set these in your `application.properties`:

| Property                           | Description                                                                                                                                             |
|------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `elysium.instance.base-url`        | Required. Public URL of the server, with no path. A real server must use a public HTTPS URL. Actor URIs and the instance domain come from it, so don't change it after federating. |
| `elysium.registration.enabled`     | Optional. Set to `true` to let anyone create an account at `/signup`. Defaults to `false`. See [Accounts and sign-ups](accounts.md). |
| `elysium.statuses.max-characters`  | Optional. The most characters a new status can have, counting its content warning. Each URL counts as 23 characters. Defaults to `5000`. Lowering it doesn't affect existing statuses. |

You also need to point `spring.datasource.*` at your PostgreSQL database. Migrations run automatically on startup.

If the server is behind a reverse proxy that handles HTTPS, set `server.forward-headers-strategy=native` so that
redirects use the public URL. Only do this if the proxy is the only way to reach the server, because the server trusts
the forwarded headers it receives.
