# Configuration

Your `application.properties` only needs the settings you want to change. Anything else uses the defaults built into the
JAR, so upgrades can update those defaults without you editing your file. If you use `SPRING_CONFIG_LOCATION` instead,
those defaults aren't loaded, and parts of the server won't work as documented.

Set these in your `application.properties`:

| Property                           | Description                                                                                                                                             |
|------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `elysium.instance.base-url`        | Required. Public URL of the server, with no path. A real server must use a public HTTPS URL. Actor URIs and the instance domain come from it, so don't change it after federating. |
| `elysium.registration.enabled`     | Optional. Set to `true` to let anyone create an account at `/signup`. Defaults to `false`. See [Accounts and sign-ups](accounts.md). |
| `elysium.rate-limit.enabled`       | Optional. Set to `false` to turn off the limits on logins and sign-ups, if you enforce your own elsewhere. Defaults to `true`. See [Rate limits](accounts.md#rate-limits). |
| `elysium.statuses.max-characters`  | Optional. The most characters a new status can have, counting its content warning. Each URL counts as 23 characters. Defaults to `5000`. Lowering it doesn't affect existing statuses. |

You also need to point `spring.datasource.*` at your PostgreSQL database. Migrations run automatically on startup.

## Reverse proxies
If the server is behind a reverse proxy, set `server.forward-headers-strategy=native` so that redirects use the public
URL and [rate limits](accounts.md#rate-limits) apply to each client's address. Without it, every client appears to have
the proxy's address and shares one limit, so one person could stop everyone from logging in.

- **The proxy must be the only way to reach the server**, because the server trusts the forwarded headers it receives.
  Anyone who can reach it directly could pretend to be any address, and so get around the limits.
- **The proxy must send `X-Forwarded-For` and `X-Forwarded-Proto`.** Caddy does this by default. nginx doesn't, so add:
  ```nginx
  proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  proxy_set_header X-Forwarded-Proto $scheme;
  ```
- **The proxy must connect from a private or loopback address**, because those are the only ones trusted by default. If
  it doesn't, add its address to `server.tomcat.remoteip.internal-proxies`, which replaces the default list.

If the server gets a login or sign-up attempt from a private or loopback address while its base URL is public, it logs
a warning once. That usually means one of these isn't set up correctly. It's expected if you're testing from the same
host or network.
