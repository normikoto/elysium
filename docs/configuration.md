# Configuration

Put only the settings you want to override in `application.properties`. The JAR supplies the other defaults and can
update them on upgrade. Use `SPRING_CONFIG_ADDITIONAL_LOCATION` to load your file alongside those defaults.
`SPRING_CONFIG_LOCATION` replaces them, which can break parts of the server.

Set these in your `application.properties`:

| Property                           | Description                                                                                                                                             |
|------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `elysium.instance.base-url`        | Required. The server's public HTTPS URL, with no path, such as `https://social.example`. Other servers know your users by it, so don't change it after federating. |
| `elysium.instance.account-domain`  | Optional. Domain in users’ addresses, such as `social.example`. Defaults to the base URL’s host. See [Account domain](#account-domain) for setup and WebFinger redirects. |
| `elysium.registration.mode`        | Optional. Who can create an account at `/signup` or in an app: `open`, `approval`, or `closed`. Defaults to `closed`. See [Sign-ups](accounts.md#sign-ups) for what each allows and what it needs. |
| `spring.mail.host`                 | Optional. The SMTP host for sign-up emails. Unset by default, which disables email. See [Email](#email). |
| `elysium.mail.from`                | Required once `spring.mail.host` is set. The address that email comes from, which your SMTP server has to allow. |
| `elysium.rate-limit.enabled`       | Optional. Set to `false` to turn off the limits on logins, sign-ups, and app registrations, if you enforce your own elsewhere. Defaults to `true`. See [Rate limits](accounts.md#rate-limits). |
| `elysium.usage.publish`            | Optional. Set to `false` to hide [account activity and weekly counts](#usage-statistics). Defaults to `true`. Elysium still records account activity when publishing is off. |
| `elysium.trends.enabled`           | Optional. Whether apps can see [trending tags, posts, and links](api.md#trends). Defaults to `true`. When set to `false`, the trends lists are empty. Elysium keeps tracking links in public posts. |
| `elysium.statuses.max-characters`  | Optional. Character limit for new posts, including the content warning and [URL counting rules](api.md#length-and-links). Defaults to `5000`. Existing posts keep their text. |
| `elysium.statuses.remote-retention` | Optional. Removes other servers' posts once they've been stored this long, such as `90d`, unless people here interacted with them. At least `1d`. Off unless set. See [Posts from other servers](#posts-from-other-servers). |
| `elysium.profiles.max-fields`      | Optional. How many fields each profile can have, from 0 to 50. Defaults to `4`, as on Mastodon. Lowering it doesn't remove any, but a profile with more can't save its fields until it has few enough. |
| `elysium.profiles.directory`       | Optional. Whether the directory lists the accounts that choose to be in it, at `/directory` and for apps. Defaults to `true`. Turning it off keeps each account's choice, which other servers still see. |
| `elysium.profiles.max-image-size`  | Optional. The largest avatar, header, or background that can be uploaded, or avatar or header downloaded from another server, such as `4MB`. Defaults to `8MB`. Larger ones from other servers are linked to their server. |
| `elysium.custom-emojis.max-image-size` | Optional. The largest image a [custom emoji](accounts.md#custom-emoji) can have, before it's re-encoded, for those admins add and those downloaded from other servers. Defaults to `256KB`, as on Mastodon. Other servers' emoji with larger images are linked to their server. |
| `elysium.custom-emojis.max-pack-size` | Optional. The largest [emoji pack](accounts.md#importing-and-exporting-packs) that can be imported on the admin page. Defaults to `50MB`. The `import-emojis` command has no limit. |
| `elysium.custom-emojis.max-pack-files` | Optional. How many files an emoji pack imported on the admin page can have, since each image is converted while the page waits. Defaults to `1000`. The `import-emojis` command has no limit. |
| `elysium.attachments.max-image-size` | Optional. Maximum image attachment size before re-encoding, such as `10MB`. Defaults to `16MB`. |
| `elysium.attachments.max-video-size` | Optional. Maximum video attachment size, such as `80MB`. Defaults to `100MB`. |
| `elysium.attachments.max-audio-size` | Optional. Maximum audio attachment size, such as `20MB`. Defaults to `40MB`. |
| `elysium.attachments.max-unattached-uploads` | Optional. Maximum unattached uploads per account. Defaults to `20`. |
| `elysium.attachments.unattached-retention` | Optional. How long an upload is kept for a post, such as `12h`. Defaults to `1d`. |
| `elysium.attachments.redraft-retention` | Optional. How long a deleted post's attachments are kept, so that it can be posted again, such as `30m`. Defaults to `1h`. |
| `elysium.attachments.remote-cache-enabled` | Optional. Set to `false` to link to other servers' attachments rather than keep copies of them. Defaults to `true`. See [Media](#media). |
| `elysium.attachments.remote-cache-retention` | Optional. How long a copy of another server's attachment, avatar, or header is kept, such as `7d`. Defaults to `30d`. See [Media](#media). |
| `elysium.attachments.remote-download-budget` | Optional. How much each other server's attachments can add up to in an hour, such as `500MB`. Defaults to `2GB`. |
| `elysium.media.directory`          | Optional. Writable directory for uploaded images, video, and audio. Defaults to `media` in the working directory. Back up the directory with the database, using the path set by `compose.yaml` or `elysium.service` if applicable. |
| `elysium.streaming.max-connections` | Optional. How many [streaming](#streaming) connections can be open at once, from all clients together. Past that, new ones are refused until others close. Defaults to `1000`. |
| `server.servlet.session.timeout`   | Optional. How long a login session lasts without a request. Defaults to `30m`. See [Login sessions](accounts.md#login-sessions). |
| `elysium.federation.inbox-limits.activities-per-minute` | Optional. How many activities each other server can deliver a minute. Defaults to `120`. See [Inbox limits](#inbox-limits). |
| `elysium.federation.inbox-limits.data-per-hour` | Optional. How much data each other server's activities can add up to in an hour, such as `50MB`. It can't be less than `1MB`. Defaults to `20MB`. |
| `elysium.federation.inbox-limits.exempt-domains` | Optional. Comma-separated domains, such as `mastodon.social`, exempt from delivery limits along with their subdomains. Use bare domains without schemes, wildcards, or public suffixes such as `co.uk`. Defaults to an empty list. |
| `elysium.federation.inbox-limits.refused-per-minute` | Optional. How many requests to the inboxes each address can have refused a minute, for being unsigned, having a signature that's wrong, or being too large. Defaults to `60`. |
| `elysium.federation.send-blocks` | Optional. Set to `false` to stop sending user blocks to other servers. Defaults to `true`. See the effects in [Blocks and mutes](accounts.md#blocks-and-mutes). |
| `elysium.federation.authorized-fetch` | Optional. Set to `true` to make other servers sign their requests to read your users' profiles, posts, and follower lists. Defaults to `false`. See [Authorized fetch](#authorized-fetch). |
| `elysium.federation.allowed-private-networks` | Optional, and should not normally be set. A comma-separated list of private networks, such as `172.30.0.0/24`, that requests to other servers may reach. Anyone can make the server send requests, so this would let them reach anything on these networks. Only for testing with servers on a private network. |
| `db-scheduler.polling-strategy` | Optional, and experimental. Set to `LOCK_AND_FETCH` to change how background jobs are picked up, which may help with more than one copy of the server. Defaults to `FETCH`. See [Background jobs](#background-jobs). |
| `db-scheduler.polling-strategy-upper-limit-fraction-of-threads` | Set to `1.0` along with `LOCK_AND_FETCH`. Leave it unset otherwise. |
| `management.server.port` | Optional. A second port, such as `9090`, that serves [metrics](#metrics) for Prometheus. Unset by default, which serves no metrics. Anyone who can reach this port can read them, without logging in. |
| `management.server.address` | Optional. The address the metrics port listens on, such as `127.0.0.1`. Defaults to every address. Ignored unless `management.server.port` is set. |

You also need to point `spring.datasource.*` at your PostgreSQL database. Migrations run automatically on startup.

## Usage statistics

NodeInfo publishes active account counts for the last 30 and 180 days, and `GET /api/v2/instance` publishes the 30-day
count. `GET /api/v1/instance/activity` publishes weekly totals for posts, logins, and sign-ups. On a server with few
users, these counts can reveal when particular users are active.

Set `elysium.usage.publish=false` to hide the active account counts in NodeInfo and disable the weekly activity
endpoint. With this setting off, `GET /api/v2/instance` reports the total local account count instead of the 30-day
count.

## Account domain

Your users can have addresses on a different domain from the server, as with Mastodon’s `LOCAL_DOMAIN` and
`WEB_DOMAIN`. These settings give `@alice@social.example` an account on `https://elysium.social.example`.

```properties
elysium.instance.base-url=https://elysium.social.example
elysium.instance.account-domain=social.example
```

**Warning:** Other servers may fail to find your users or accept their posts unless you redirect WebFinger requests.
Redirect `/.well-known/webfinger` on `social.example` and keep the query string. With nginx, add this to the `server`
block for `social.example`.

```nginx
location = /.well-known/webfinger {
    return 301 https://elysium.social.example$request_uri;
}
```

With Caddy, add this to the site block for `social.example`.

```caddy
redir /.well-known/webfinger https://elysium.social.example{uri} 301
```

Addresses on the base URL’s domain, such as `@alice@elysium.social.example`, still resolve to the same users in
searches, mentions, and requests from other servers.

On an existing server, user addresses change at the next startup. Followers, posts, and links keep working because
they use the base URL. Other servers pick up the new addresses when they refresh accounts by URL.

Addresses on the old account domain stop resolving unless it is the base URL’s domain. Elysium refuses to start if it
already knows remote accounts on the proposed domain.

To check the redirect, run `curl -L
'https://social.example/.well-known/webfinger?resource=acct:alice@social.example'`. Expect `acct:alice@social.example`
as the `subject` and `https://elysium.social.example/users/alice` as the `self` link.

## Email
Email is needed for the `open` and `approval` [sign-up modes](accounts.md#sign-ups). Along with `spring.mail.host` and
`elysium.mail.from`:
- Set `spring.mail.port`, `spring.mail.username`, and `spring.mail.password` if your SMTP server needs them.
- Set `spring.mail.properties.mail.smtp.starttls.required=true`, so that email isn't sent unencrypted, unless the server
  uses port 465 with `spring.mail.protocol=smtps`.

## Media

Elysium re-encodes attached images and strips metadata such as location. Video and audio keep their original metadata,
including any location or device details. Anyone who can see the post can download its attachments.

Elysium serves uploads from `/media` on its own domain and sends headers that keep browsers from treating files as
pages. Attached files take up disk space until their post is deleted. Each upload type has its own size limit, checked
before Elysium reads the body. A request gets up to 4 MB beyond its file limits for the rest of the form.

Uploads require a content length. Chunked requests without one get a 411. Browsers send the length, and nginx normally
supplies it by buffering the request. A proxy such as Caddy may pass a chunked upload through as it arrives, so
Elysium rejects it.

Elysium caches attachments from the remote posts it stores so your users can load the copies without exposing their
addresses to the remote server. It applies the same checks, size limits, and re-encoding as for uploads. Oversized or
unsupported files appear as links to the originals.

Cached files expire after `remote-cache-retention`. Elysium downloads missing copies on demand when someone views the
post. After a server reaches its hourly `remote-download-budget`, its files appear as links until the budget has room
and someone views them again.

Lowering retention saves disk space but can make frequently viewed attachments download more often. If disk space is
limited, you can turn off caching.

Remote avatars and headers use the same cache and hourly budget. Elysium downloads expired copies when someone views
the account or when its profile is refreshed. It replaces copies when the account changes its images and removes them
when the server is suspended or its media is rejected.

## Posts from other servers

Elysium keeps the posts it receives from other servers for good unless you set `remote-retention`. Once you set it,
Elysium removes other servers' posts that have been stored for longer, a batch at a time, so a large backlog takes a while
to clear. Removed posts can't be restored from your server, but they're stored again if they're fetched or delivered
again, such as when someone opens a link to one or searches for it.

Elysium keeps a post if:

- someone here favourited, boosted, bookmarked, reacted to, voted in, quoted, filtered, or reported it
- it mentions someone here, or is in their notifications or direct messages
- it's in a thread that someone here posted in, or a thread with posts newer than the retention period
- it's pinned to a profile, or quoted or boosted by a post that's kept

Removal has these effects:

- Older posts disappear from hashtag pages, search results, public timelines, and other servers' profiles, and home
  timelines have gaps further back than the retention period.
- Post counts on other servers' profiles go down.
- Their attachments' files are deleted from your media directory.
- Other servers aren't told, since only the author's server can delete a post.

A delivery that would exceed the server’s activity count or data limit gets `429`. Mastodon and other common servers
retry those deliveries automatically. The limits protect your database from one server sending too much data.

Subdomains such as `a.example.com` and `b.example.com` share a limit. To exempt a trusted server that regularly
reaches its limit, add it to `elysium.federation.inbox-limits.exempt-domains`.

Elysium limits refused deliveries by client IP address. After 60 counted refusals in a minute by default, every
delivery from that address gets `429` until the window resets, including valid deliveries. Oversized requests and
certain signature failures count, so a server that just changed keys can hit this limit. Domain exemptions apply only
to the per-server delivery limits.

IPv6 addresses are grouped by /64. The limit needs the real client address from your [reverse
proxy](#reverse-proxies). For a public server, Elysium skips this limit on requests from its own host or private
network, where the address may belong to a proxy shared by many clients.

## Authorized fetch
By default, public posts, profiles, and follower lists are available as unsigned ActivityPub documents. Set
`elysium.federation.authorized-fetch=true` to require signed requests, as with Mastodon’s `AUTHORIZED_FETCH`. Elysium
then rejects requests signed by [blocked servers](accounts.md#domain-blocks) or suspended remote accounts.

Public web pages stay accessible to anyone, including blocked servers. A blocked server can also have an account on
another server sign the request. Servers that never sign requests can still follow your users and receive posts, but
they cannot fetch posts or follower lists. Mastodon, GoToSocial, and Misskey sign their requests.

## Background jobs
Elysium instances that share a database also share the background job queue. `LOCK_AND_FETCH` lets each instance pick
up jobs that other instances have not claimed. The setting is experimental and has only been tested with a single
instance, where it made no difference.

To try it, set both properties.
```properties
db-scheduler.polling-strategy=LOCK_AND_FETCH
db-scheduler.polling-strategy-upper-limit-fraction-of-threads=1.0
```

Set the fraction to `1.0` so each instance claims only as many jobs as it can run. Otherwise it holds about three
times as many, and if it stops, the other instances may take a long time to run the jobs it held.

Remove both settings and restart to restore the defaults.

## Metrics
Elysium can serve metrics in Prometheus's format, for Prometheus, VictoriaMetrics, Grafana Alloy, or anything else that
scrapes it. They're off until you give them a port of their own:
```properties
management.server.port=9090
# Unless the scraper runs on another host
management.server.address=127.0.0.1
```
With Docker Compose, set `MANAGEMENT_SERVER_PORT` in `.env` instead, and don't publish the port on a public interface.
Put the scraper in the same Compose project, or publish it on `127.0.0.1` as `compose.yaml` does with the main port.

**Warning:** Metrics need no login, and they show how busy the server is and when. Never route the metrics port through
your reverse proxy, or allow it through the firewall to the internet.

The health check at `/actuator/health` answers on both ports. Restart, and then check that it worked:
```shell
curl http://127.0.0.1:9090/actuator/prometheus
```
You should see lines starting with `elysium_`. Requests to `/actuator/prometheus` on the main port stay refused.

Besides the usual metrics for the JVM, HTTP requests, and database connections, these show how federation is doing:

- `elysium_federation_deliveries_seconds` times each attempt to deliver an activity to another server, by `outcome`:
  `delivered`, `refused` for an answer that won't change, `retryable` for an error or a busy server, `unreachable`,
  `too_large`, `no_connection` when all of Elysium's own connections were in use, `not_allowed` for an inbox on a
  private network or a blocked server, `invalid_inbox`, or `error`.
- `elysium_federation_inbox_requests_total` counts deliveries from other servers by `outcome`, such as `accepted`,
  `rate_limited`, `signature_rejected`, or `error` for one that failed with a 500.
- `elysium_jobs_queued` counts background jobs by `task` and `state`: `due` is waiting for a worker, and a steady rise
  means the server is falling behind. `parked` jobs were given up on, and can be looked at in `/admin/db-scheduler`.
- `elysium_jobs_oldest_due_seconds` is how late the most overdue job of each task is.
- `dbscheduler_task_completions_total` counts runs of each task. A run that only waited for a busy server counts as `ok`.

With more than one instance, each reports the whole job queue, so take the maximum of `elysium_jobs_*` rather than the
sum. The job counts can be up to 30 seconds old.

## Streaming
Apps stream new posts and notifications at `/api/v1/streaming`. To pass WebSocket connections through nginx, add this
location to the server block.
```nginx
location /api/v1/streaming {
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    # Longer than the server's 30 seconds between heartbeats.
    proxy_read_timeout 1h;
    # The same proxy_pass and proxy_set_header lines as the server's other locations
}
```
Caddy passes WebSockets on by default. Without this, apps fall back to checking for updates, or show an error.

**Warning:** The proxy access log can expose tokens when apps send them in the streaming URL. Omit the query string
from logs for `/api/v1/streaming` or restrict access to those logs.

Each instance streams only the updates it handles, so apps can miss updates when you run multiple instances. Elysium
refuses new connections once it reaches `elysium.streaming.max-connections`.

## Reverse proxies
Use a reverse proxy to handle HTTPS for a public server. A proxy limit can stop floods before Elysium
applies its own [inbox limits](#inbox-limits). Allow enough traffic for valid deliveries because large servers can send them from just a few addresses. With
nginx, use a rule like this.
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

nginx's default `client_max_body_size` of `1m` already matches the largest activity the server accepts, but it also
refuses larger uploads, such as images and video attached to posts. Raise it in the server block to a little over your
largest upload limit, such as `client_max_body_size 125m;` for the defaults, which allow a 100 MB video with a 16 MB
thumbnail, and keep `1m` in the inbox location. Caddy needs a third-party module, such as `caddy-ratelimit`, to limit
requests.

Set `server.forward-headers-strategy=native` behind a reverse proxy. This lets Elysium use the public URL for
redirects, including Swagger UI’s OAuth redirect, and the client address for [rate limits](accounts.md#rate-limits).
Otherwise clients can share the proxy’s limit and block each other’s logins or deliveries.

**Warning:** If Elysium trusts forwarded addresses supplied by clients, they can bypass IP-based rate limits. Apply
all of the following proxy settings.

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
