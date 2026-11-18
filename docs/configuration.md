# Configuration

Your `application.properties` only needs the settings you want to change. Anything else uses the defaults built into the
JAR, so upgrades can update those defaults without you editing your file. If you use `SPRING_CONFIG_LOCATION` instead,
those defaults aren't loaded, and parts of the server won't work as documented.

Set these in your `application.properties`:

| Property                           | Description                                                                                                                                             |
|------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `elysium.instance.base-url`        | Required. The server's public HTTPS URL, with no path, such as `https://social.example`. Other servers know your users by it, so don't change it after federating. |
| `elysium.registration.mode`        | Optional. Who can create an account at `/signup`: `open`, `approval`, or `closed`. Defaults to `closed`. See [Sign-ups](accounts.md#sign-ups) for what each allows and what it needs. |
| `spring.mail.host`                 | Optional. The SMTP server that sends email, such as the links that confirm a sign-up's address. Without one, no email is sent. See [Email](#email). |
| `elysium.mail.from`                | Required once `spring.mail.host` is set. The address that email comes from, which your SMTP server has to allow. |
| `elysium.rate-limit.enabled`       | Optional. Set to `false` to turn off the limits on logins, sign-ups, and app registrations, if you enforce your own elsewhere. Defaults to `true`. See [Rate limits](accounts.md#rate-limits). |
| `elysium.statuses.max-characters`  | Optional. The most characters a new post can have, counting its content warning. Each URL counts as 23 characters. Defaults to `5000`. Lowering it doesn't affect existing posts. |
| `elysium.profiles.max-fields`      | Optional. How many fields each profile can have, from 0 to 50. Defaults to `4`, as on Mastodon. Lowering it doesn't remove any, but a profile with more can't save its fields until it has few enough. |
| `elysium.profiles.max-image-size`  | Optional. The largest avatar or header that can be uploaded, or downloaded from another server, such as `4MB`. Defaults to `8MB`. Larger ones from other servers are linked to their server. |
| `elysium.custom-emojis.max-image-size` | Optional. The largest image a [custom emoji](accounts.md#custom-emoji) can have, before it's re-encoded, for those admins add and those downloaded from other servers. Defaults to `256KB`, as on Mastodon. Other servers' emoji with larger images are linked to their server. |
| `elysium.custom-emojis.max-pack-size` | Optional. The largest [emoji pack](accounts.md#importing-and-exporting-packs) that can be imported on the admin page. Defaults to `50MB`. The `import-emojis` command has no limit. |
| `elysium.custom-emojis.max-pack-files` | Optional. How many files an emoji pack imported on the admin page can have, since each image is converted while the page waits. Defaults to `1000`. The `import-emojis` command has no limit. |
| `elysium.attachments.max-image-size` | Optional. The largest image that can be attached to a post, before it's re-encoded. Defaults to `16MB`. |
| `elysium.attachments.max-video-size` | Optional. The largest video that can be attached to a post. Defaults to `100MB`. |
| `elysium.attachments.max-audio-size` | Optional. The largest audio file that can be attached to a post. Defaults to `40MB`. |
| `elysium.attachments.max-unattached-uploads` | Optional. How many uploads each account can have that aren't in a post yet. Defaults to `20`. |
| `elysium.attachments.unattached-retention` | Optional. How long an upload is kept for a post, such as `12h`. Defaults to `1d`. |
| `elysium.attachments.redraft-retention` | Optional. How long a deleted post's attachments are kept, so that it can be posted again, such as `30m`. Defaults to `1h`. |
| `elysium.attachments.remote-cache-enabled` | Optional. Set to `false` to link to other servers' attachments rather than keep copies of them. Defaults to `true`. See [Media](#media). |
| `elysium.attachments.remote-cache-retention` | Optional. How long a copy of another server's attachment, avatar, or header is kept, such as `7d`. Defaults to `30d`. See [Media](#media). |
| `elysium.attachments.remote-download-budget` | Optional. How much each other server's attachments can add up to in an hour, such as `500MB`. Defaults to `2GB`. |
| `elysium.media.directory`          | Optional. Where uploaded images, video, and audio are kept. Defaults to `media` in the working directory. The server has to be able to write to it, and you should back it up along with the database, which refers to the files in it. `compose.yaml` and `elysium.service` already set this. |
| `elysium.streaming.max-connections` | Optional. How many [streaming](#streaming) connections can be open at once, from all clients together. Past that, new ones are refused until others close. Defaults to `1000`. |
| `server.servlet.session.timeout`   | Optional. How long a login session lasts without a request. Defaults to `30m`. See [Login sessions](accounts.md#login-sessions). |
| `elysium.federation.inbox-limits.activities-per-minute` | Optional. How many activities each other server can deliver a minute. Defaults to `120`. See [Inbox limits](#inbox-limits). |
| `elysium.federation.inbox-limits.data-per-hour` | Optional. How much data each other server's activities can add up to in an hour, such as `50MB`. It can't be less than `1MB`. Defaults to `20MB`. |
| `elysium.federation.inbox-limits.exempt-domains` | Optional. A comma-separated list of domains, such as `mastodon.social`, that aren't limited, along with their subdomains. Enter only the domain, with no `https://` or wildcard. A suffix that anyone can register under, such as `co.uk`, isn't allowed. Empty by default. |
| `elysium.federation.inbox-limits.refused-per-minute` | Optional. How many requests to the inboxes each address can have refused a minute, for being unsigned, having a signature that's wrong, or being too large. Defaults to `60`. |
| `elysium.federation.send-blocks` | Optional. Set to `false` to stop telling people on other servers that a user has blocked them. Their server then keeps showing them the user's public posts. The follows between them end on both servers either way. Defaults to `true`. See [Blocks and mutes](accounts.md#blocks-and-mutes). |
| `elysium.federation.authorized-fetch` | Optional. Set to `true` to make other servers sign their requests to read your users' profiles, posts, and follower lists. Defaults to `false`. See [Authorized fetch](#authorized-fetch). |
| `elysium.federation.allowed-private-networks` | Optional, and should not normally be set. A comma-separated list of private networks, such as `172.30.0.0/24`, that requests to other servers may reach. Anyone can make the server send requests, so this would let them reach anything on these networks. Only for testing with servers on a private network. |
| `db-scheduler.polling-strategy` | Optional, and experimental. Set to `LOCK_AND_FETCH` to change how background jobs are picked up, which may help with more than one copy of the server. Defaults to `FETCH`. See [Background jobs](#background-jobs). |
| `db-scheduler.polling-strategy-upper-limit-fraction-of-threads` | Set to `1.0` along with `LOCK_AND_FETCH`. Leave it unset otherwise. |

You also need to point `spring.datasource.*` at your PostgreSQL database. Migrations run automatically on startup.

## Email
Email is needed for the `open` and `approval` [sign-up modes](accounts.md#sign-ups). Along with `spring.mail.host` and
`elysium.mail.from`:
- Set `spring.mail.port`, `spring.mail.username`, and `spring.mail.password` if your SMTP server needs them.
- Set `spring.mail.properties.mail.smtp.starttls.required=true`, so that email isn't sent unencrypted, unless the server
  uses port 465 with `spring.mail.protocol=smtps`.

## Media

Images attached to posts are re-encoded, which removes their metadata, such as where a photo was taken. Video and audio
are kept exactly as they were uploaded, so their metadata stays, including any location or device details, and anyone who
can see the post can download it. Tell your users if that matters to them.

Uploads are served from `/media` on your server's own domain, with headers that stop browsers from treating them as
pages. Every file attached to a post counts toward your disk space until the post is deleted, so set the size limits with
your storage in mind. Each kind of upload is held to its own limits before any of it is received, so raising the
video limit doesn't let anyone upload a 100 MB avatar. A request is allowed its files plus 4 MB for the rest of the form.

Uploads have to say how large they are, and one sent in chunks without a size gets a 411. Browsers always give the size,
and so does nginx when it passes on a request, since it reads the whole body first by default. A proxy that passes
request bodies on as they arrive, such as Caddy, also passes on an app's chunked upload, which the server then refuses.

The server also keeps copies of the attachments on other servers' posts that it stores, so that your users' browsers
load them from you rather than from those servers, which would see their addresses. Copies are checked and re-encoded
like uploads, and ones over the same size limits, or that aren't an accepted format, are linked instead. A copy is
removed after `remote-cache-retention`, and downloaded again the next time someone views the post. One that hasn't been
downloaded yet is downloaded as soon as someone views it. Each other server can only have `remote-download-budget`
downloaded in an hour, and past that its attachments are linked until someone views them once there's room again. These
copies can take a lot of space on a server that follows many accounts. Lowering the retention removes copies sooner,
whether or not they're still viewed, so posts people keep viewing are downloaded again more often. Turn copies off if
you're short of disk.

Avatars and headers of accounts on other servers are copied the same way, and count toward the same hourly limit. Their
copies are also removed after `remote-cache-retention`, and downloaded again the next time someone views the account,
or when its profile is next fetched. They're also replaced when the account changes its image, and removed when its server
is blocked or its media is rejected.

## Inbox limits
Each other server can only deliver so many activities, and so much data, before it gets a `429` and has to try again
later. Mastodon and other common servers do this on their own, so nothing is lost. The defaults leave plenty of room for a
small server federating with large ones, while stopping one server from filling your database.

A server is counted by its domain, so `a.example.com` and `b.example.com` share one limit. If you trust a busy server
that's reaching its limit, add it to `elysium.federation.inbox-limits.exempt-domains` rather than raising the limits for
every server.

Anyone can send requests that aren't signed, or whose signature is wrong, each with up to 1 MiB of data, and the server
has to read every one. These are limited by address instead: one that has 60 refused in a minute, by default, gets a `429`
for everything it sends to the inboxes until the minute is up, including valid deliveries, which are tried again later.
Only these refusals count, so however much a real server delivers, it only gets near this limit in a burst of signatures
that are wrong, such as just after it changes its keys. Exempt domains don't affect it. IPv6 addresses are counted by their
/64 network. The limit needs the server to see each client's own address, so check your [reverse proxy](#reverse-proxies).
If requests come from the server's own host or private network, although its URL is public, the limit is skipped, since
that usually means the proxy is hiding its clients' addresses, and one client could otherwise stop every server
delivering.

## Authorized fetch
By default, anyone can read your users' public posts, profiles, and follower lists as ActivityPub documents, without saying
who they are, so a server you've [blocked](accounts.md#domain-blocks) can still read them. With
`elysium.federation.authorized-fetch=true`, as with Mastodon's `AUTHORIZED_FETCH`, other servers have to sign these
requests, and a blocked server, or a suspended account on another server, is refused.

This doesn't make anything private. Web pages, such as profiles and public posts, can still be read by anyone, and a
blocked server can read them that way too. It can also have an account on another server sign the requests for it. Servers that
never sign their requests can't read your users' posts or follower lists while it's on, though they can still follow your
users and receive their posts. Mastodon, GoToSocial, and Misskey all sign them.

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

## Streaming
Apps can keep a connection open at `/api/v1/streaming` to see new posts and notifications as they happen, rather than
checking every so often. Most connect with a WebSocket, which nginx only passes on with these lines in the server
block:
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

Apps that can't send headers put their token in the URL, as on Mastodon, so your proxy's access log can contain tokens.
Leave the query string out of the log for `/api/v1/streaming`, or keep the log as private as the tokens.

Each copy of the server only streams what it handles itself, so with more than one copy, apps miss updates that another
copy handled. Streaming connections count towards `elysium.streaming.max-connections`, and past it new ones are refused.

## Reverse proxies
A public server needs a reverse proxy in front of it, to handle HTTPS. The server [limits](#inbox-limits) what can be sent
to its inboxes on its own, but a request still takes a connection before the server can refuse it, and requests sent at
the same time are all read before any of them counts. To stop floods before they reach the server, you can also limit how
many requests each address can send to the inboxes. This limit has to be
generous, because valid deliveries count too, and a large server sends them from only a few addresses. For example, with
nginx:
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
