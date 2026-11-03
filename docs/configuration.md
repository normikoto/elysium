# Configuration

Your `application.properties` only needs the settings you want to change. Anything else uses the defaults built into the
JAR, so upgrades can update those defaults without you editing your file. If you use `SPRING_CONFIG_LOCATION` instead,
those defaults aren't loaded, and parts of the server won't work as documented.

Set these in your `application.properties`:

| Property                           | Description                                                                                                                                             |
|------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `elysium.instance.base-url`        | Required. Public URL of the server, with no path. A real server must use a public HTTPS URL. Actor URIs and the instance domain come from it, so don't change it after federating. |
| `elysium.registration.enabled`     | Optional. Set to `true` to let anyone create an account at `/signup`, once the first account exists. That account has to come from the `create-user` command. Defaults to `false`. See [Accounts and sign-ups](accounts.md). |
| `elysium.rate-limit.enabled`       | Optional. Set to `false` to turn off the limits on logins and sign-ups, if you enforce your own elsewhere. Defaults to `true`. See [Rate limits](accounts.md#rate-limits). |
| `elysium.statuses.max-characters`  | Optional. The most characters a new status can have, counting its content warning. Each URL counts as 23 characters. Defaults to `5000`. Lowering it doesn't affect existing statuses. |

You also need to point `spring.datasource.*` at your PostgreSQL database. Migrations run automatically on startup.

## Reverse proxies
If the server is behind a reverse proxy, set `server.forward-headers-strategy=native` so that redirects use the public
URL and [rate limits](accounts.md#rate-limits) apply to each client's address. Without it, every client appears to have
the proxy's address and shares one limit, so one person could stop everyone from logging in.

**Warning:** With that setting on, a wrong proxy configuration can turn the per-address limits off without any sign of a
problem. If the server trusts an `X-Forwarded-For` value that a client wrote, the client can send a new address with
every request. Check all of these:

- **The proxy must be the only way to reach the server.** Anyone who can reach it directly can send any header.
- **The proxy must add the client's real address to `X-Forwarded-For`,** and not just pass on what the client sent. It
  must also send `X-Forwarded-Proto` and the original `Host`. Caddy does all of this by default. nginx passes on
  `X-Forwarded-For` from the client unchanged and replaces `Host`, so you need to add:
  ```nginx
  proxy_set_header Host $host;
  proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  proxy_set_header X-Forwarded-Proto $scheme;
  ```
- **The proxy must connect from a private or loopback address**, because those are the only ones trusted by default. If
  it doesn't, add its address to `server.tomcat.remoteip.internal-proxies`, which replaces the default list.

If the server gets a login or sign-up attempt from a private or loopback address while its base URL is public, it logs
a warning once. That usually means it's seeing the proxy's address instead of the client's. It's expected if you're
testing from the same host or network. The warning can't detect made-up addresses, because they look like any other
client's.
