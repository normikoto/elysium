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
| `server.servlet.session.timeout`   | Optional. How long a login session lasts without a request. Defaults to `30m`. See [Login sessions](accounts.md#login-sessions). |
| `elysium.federation.inbox-limits.activities-per-minute` | Optional. How many activities each other server can deliver a minute. Defaults to `120`. See [Inbox limits](#inbox-limits). |
| `elysium.federation.inbox-limits.data-per-hour` | Optional. How much data each other server's activities can add up to in an hour, such as `50MB`. It can't be less than `1MB`. Defaults to `20MB`. |
| `elysium.federation.inbox-limits.exempt-domains` | Optional. A comma-separated list of domains, such as `mastodon.social`, that aren't limited, along with their subdomains. Enter only the domain, with no `https://` or wildcard. A suffix that anyone can register under, such as `co.uk`, isn't allowed. Empty by default. |
| `elysium.federation.allowed-private-networks` | Optional, and should not normally be set. A comma-separated list of private networks, such as `172.30.0.0/24`, that requests to other servers may reach. Anyone can make the server send requests, so this would let them reach anything on these networks. Only for testing with servers on a private network. |
| `db-scheduler.polling-strategy` | Optional, and experimental. Set to `LOCK_AND_FETCH` to change how background jobs are picked up, which may help with more than one copy of the server. Defaults to `FETCH`. See [Background jobs](#background-jobs). |
| `db-scheduler.polling-strategy-upper-limit-fraction-of-threads` | Set to `1.0` along with `LOCK_AND_FETCH`. Leave it unset otherwise. |

You also need to point `spring.datasource.*` at your PostgreSQL database. Migrations run automatically on startup.

## Inbox limits
Each other server can only deliver so many activities, and so much data, before it gets a `429` and has to try again
later. Mastodon and other common servers do this on their own, so nothing is lost. The defaults leave plenty of room for a
small server federating with large ones, while stopping one server from filling your database.

A server is counted by its domain, so `a.example.com` and `b.example.com` share one limit. If you trust a busy server
that's reaching its limit, add it to `elysium.federation.inbox-limits.exempt-domains` rather than raising the limits for
every server. These limits don't apply to requests that aren't signed, or whose signature is wrong, which is why a public
server needs a [reverse proxy that limits them](#reverse-proxies).

## Background jobs
Sending activities to other servers, and other background work, happens in jobs stored in the database, which any copy of
the server can pick up. By default, copies sharing a database can try to pick up the same jobs, and all but one have to
look again. With `LOCK_AND_FETCH`, each copy only picks up jobs no other copy has. This is experimental: it made no
difference with one copy, and hasn't been tested with more.

To try it, set both:
```properties
db-scheduler.polling-strategy=LOCK_AND_FETCH
db-scheduler.polling-strategy-upper-limit-fraction-of-threads=1.0
```

Don't leave out the second one. Without it, each copy holds about three times as many jobs as it can run at once, and
other copies can't run them while it does. If a copy stops, other copies can take a long time to run the jobs it held.

To go back, remove both settings and restart.

## Reverse proxies
A public server needs a reverse proxy in front of it, to handle HTTPS, and to limit how many requests each address can
send to the inboxes. The server limits what other servers can deliver, but not requests that aren't signed or whose
signature is wrong. Anyone can send those, each with up to 1 MiB of data, and the server has to read every one.

The limit has to be generous, because valid deliveries count too, and a large server sends them from only a few addresses.
For example, with nginx:
```nginx
# In the http block
limit_req_zone $binary_remote_addr zone=elysium_inbox:10m rate=10r/s;

# In the server block, next to your other location blocks for Elysium
location ~ ^/(inbox|actor/inbox|users/[^/]+/inbox)$ {
    limit_req zone=elysium_inbox burst=200 nodelay;
    limit_req_status 429;
    # The same proxy_pass and proxy_set_header lines as the server's other locations
}
```

nginx's default `client_max_body_size` of `1m` already matches the largest activity the server accepts. Caddy needs a
third-party module, such as `caddy-ratelimit`, to limit requests.

Behind a reverse proxy, set `server.forward-headers-strategy=native` so that redirects use the public
URL, including Swagger UI's OAuth redirect, and [rate limits](accounts.md#rate-limits) apply to each client's address.
Without it, every client appears to have the proxy's address and shares one limit, so one person could stop everyone from
logging in, or stop other servers from delivering activities.

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

If the server logs a warning about requests from a private or loopback address, it's probably seeing the proxy's address
instead of the client's. That's expected if you're testing from the same host or network. It can't warn about made-up
addresses.
