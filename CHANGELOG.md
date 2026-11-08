# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Mention and follow notifications, including grouped notifications for newer Mastodon clients, unread counts, and
  saved read positions. Notifications start with new activity after upgrading.
- Favourites of local and remote posts, through `POST /api/v1/statuses/:id/favourite` and `/unfavourite`, which are
  federated as `Like` and `Undo`. Likes from other servers count toward `favourites_count` and notify the post's author
  with a `favourite` notification.
- Boosts, through `POST /api/v1/statuses/:id/reblog` and `/unreblog`, which are federated as `Announce` and `Undo`. Boosts
  show in the home timeline, on account pages, and on profile pages. Boosts of local posts from other servers count toward
  `reblogs_count` and notify the post's author with a `reblog` notification. Boosts by accounts that local users follow
  show in their home timeline, and the boosted post is fetched from its server if it isn't stored yet. `exclude_reblogs`
  on an account's statuses now leaves boosts out.
- Direct-message conversations with per-user read and hide controls. Existing direct messages appear as read
  conversations after upgrading.
- Editing posts, through `PUT /api/v1/statuses/:id`. Edits are sent to the servers that were sent the post, and accounts
  an edit mentions for the first time get a `mention` notification. Every version of a post is kept, from this server's
  posts and the edits that other servers send, and `GET /api/v1/statuses/:id/history` lists them.
  `GET /api/v1/statuses/:id/source` gives a post's text for editing it. Versions from before upgrading aren't there. See
  `docs/api.md` and `FEDERATION.md`.
- Other servers can fetch a followers-only or direct post again when they sign the request as an account that was sent
  it, such as a follower or a mentioned account. Before, they got a 404, so they couldn't refresh it or fill in a thread.

- Initial scaffolding
- Local accounts with username and password login, hashed with Argon2id
- A sign-up page, at `/signup`. `elysium.registration.mode` decides who can use it: `closed`, the default, for only people
  with an invite, `open` for anyone who confirms their email address, or `approval` for those who also wait for an admin.
  `open` and `approval` need an SMTP server, set with `spring.mail.*` and `elysium.mail.from`.
- Invites, which let someone sign up in any mode without an email address or approval, at `/admin/invites` or with the
  `create-invite`, `list-invites`, and `revoke-invite` commands
- Approving sign-ups at `/admin/sign-ups`, or with the `list-sign-ups`, `approve-sign-up`, and `reject-sign-up` commands
- `email` in the admin API's accounts, for those that confirmed one. `approval_required` in `GET /api/v1/instance`
- `create-user` command to create a local account, and `reset-password` command to replace an account's password and
  revoke its applications' access
- An admin role. The first account from `create-user` becomes an admin, and the `set-role` command gives or removes the
  role. The sign-up page refuses to create the first account, and `admin:*` scopes only work for admins
- Mastodon-compatible OAuth 2: app registration, authorization code and client credentials grants, token revocation,
  scopes, and server metadata
- `GET /api/v1/apps/verify_credentials` and `GET /api/v1/accounts/verify_credentials`
- Statuses: `POST /api/v1/statuses`, `GET /api/v1/statuses/:id`, `DELETE /api/v1/statuses/:id`, and
  `GET /api/v1/accounts/:id/statuses`
- `GET /api/v1/timelines/public`
- `GET /api/v1/instance`, which tells clients the post length limit
- NodeInfo 2.0 and 2.1 at `/nodeinfo/2.0` and `/nodeinfo/2.1`, with discovery at `/.well-known/nodeinfo`
- `elysium.statuses.max-characters` to set the post length limit, which defaults to 5000
- Public API documentation, with Swagger UI at `/api-docs/ui` and the OpenAPI document at `/api-docs`
- A front page at `/`, and public pages for profiles at `/users/:username` and statuses at
  `/users/:username/statuses/:id`
- `application.properties.example`, a starting point for your own config
- Production Docker Compose setup in `compose.yaml`, which runs the image from `./gradlew bootBuildImage`
- Rate limits on failed logins and on sign-ups, on by default and turned off with `elysium.rate-limit.enabled=false`.
  Behind a reverse proxy, these need `server.forward-headers-strategy=native` and a proxy that adds the client's address
  to `X-Forwarded-For`. Otherwise all clients can share one limit, or clients can get around the limits by making up
  their addresses. nginx needs configuring to do this. See `docs/configuration.md`.
- An instance actor at `/actor`, which can be found through WebFinger as `instance.actor`. It's for signing requests that
  no user is behind.
- Other servers' users can follow local users and unfollow them. Every request is accepted automatically, and an `Accept`
  is sent back. See `FEDERATION.md`.
- Local users can follow and unfollow other local users, and users on other servers, which they can find by
  `username@domain` or by URL. Following another server's user waits until that server accepts. Followers can see a local user's followers-only posts. This adds `GET /api/v1/accounts/:id`,
  `GET /api/v1/accounts/lookup`, `GET /api/v1/accounts/relationships`, `POST /api/v1/accounts/:id/follow` and `/unfollow`,
  `GET /api/v1/accounts/:id/followers` and `/following`, and `GET /api/v2/search`. See `docs/api.md` and `FEDERATION.md`.
- New statuses are sent to followers on other servers, as a `Create`, and statuses from before this aren't sent.
  Followers-only statuses only go to those already following when they're posted. See `FEDERATION.md`.
- Mentions, written as `@user` for someone on this server or `@user@domain` for someone on another, are linked to the
  account, which is looked up on its server when the status is posted. A mentioned account can see a followers-only status,
  and direct statuses go only to the accounts they mention. Without notifications or conversations, a direct status, or a
  followers-only one that mentions a user, shows in that user's home timeline. Posts from other servers that mention a
  local user, and direct ones addressed to them, are stored and shown to them. See `docs/api.md` and `FEDERATION.md`.
- Deleting a status stops any `Create` that hasn't started sending, and sends a `Delete` to followers on other servers and
  the accounts it mentioned. For a followers-only status, this only goes to followers who were already following when it was
  posted. A `Delete` waits for a `Create` that's being sent to the same server. See `FEDERATION.md`.
- A deleted public or unlisted status now tells other servers it's gone, with a `410`, rather than a 404.
- `GET /api/v1/timelines/home`, with the user's own posts and those of the accounts they follow, on this server and
  others. See `docs/api.md`.
- Posts from other servers are stored when they're sent here, which is when a local user follows their author or is
  addressed by them. They're shown through the API, in `GET /api/v1/statuses/:id`, `GET /api/v1/accounts/:id/statuses`,
  and `GET /api/v1/timelines/public`, which now takes `local` and `remote`, but not on the public web pages. Older posts
  aren't fetched, and attachments aren't read. Their HTML is
  sanitized. A public or unlisted reply to one is also sent to its author. A domain block removes its server's posts, and
  a suspended account's posts are hidden until the suspension ends. The server's post count only counts local posts. See
  `FEDERATION.md` and `docs/api.md`.
- Posts and accounts deleted on other servers are now removed here too, along with a deleted account's follows. A deleted
  account from another server can't come back, even if its server gives its address to a new account. See `FEDERATION.md`.
- Replies from other servers to posts by local users, or by accounts that local users follow, are now stored even when they
  don't address anyone here, including the replies that Mastodon forwards to the followers of the account being replied to.
  `GET /api/v1/statuses/:id/context` shows a post's thread, from what's stored here. See `FEDERATION.md` and `docs/api.md`.
- Fetches that other servers can point this server at, such as of forwarded activities, are now limited for each target
  server, and fetches of any kind run at most 4 at once for one server. See `FEDERATION.md`.
- Posts edited on other servers are now updated here too, with `edited_at` set in the API. Changes to accounts on other servers, such as a new key or inbox, are picked up as soon as the account's server
  announces them. See `FEDERATION.md`.
- Actor documents link to `followers` and `following` collections, at `/users/:username/followers` and
  `/users/:username/following`, which list accounts in pages.
- A `/settings` page, and `PATCH /api/v1/accounts/update_credentials` with `hide_collections`, for people to hide who follows
  them and who they follow. How many there are is still shown.
- A background job queue stored in PostgreSQL, for sending and receiving activities, which needs no new services. Admins can
  see, retry, and delete jobs at `/admin/db-scheduler`. See `docs/accounts.md`.
- Inboxes for activities from other servers, which now makes this server send requests to other servers. Inboxes have a
  rate limit that `elysium.rate-limit.enabled` doesn't turn off, and behind a reverse proxy it needs the same setup as the
  others. See `FEDERATION.md` and `docs/configuration.md`.
- Limits on how many activities, and how much data, each other server can deliver: 120 activities a minute and 20 MiB an
  hour by default, counting subdomains as the same server. Change them with `elysium.federation.inbox-limits.*`, or stop
  limiting servers you trust with `elysium.federation.inbox-limits.exempt-domains`. See `docs/configuration.md`.
- A public server needs a reverse proxy that limits requests to the inboxes, since the server doesn't limit ones that
  aren't signed or have a wrong signature. If you run one, add this limit to your proxy. See `docs/configuration.md`.
- `elysium.federation.allowed-private-networks`, which lets requests to other servers reach the private networks it lists.
  It should not normally be set: it's only for servers on a private network, such as the federation test lab.
- Domain blocks, for admins to cut off another server and its subdomains, at `/admin/domain-blocks` and
  `/api/v1/admin/domain_blocks`. Blocked servers get a `403`, nothing is fetched from them or sent to them, and their follows
  are removed. See `docs/accounts.md` and `FEDERATION.md`.
- Suspending accounts, for admins to stop one local or remote account in a way that can be undone, and deleting local accounts
  for good. These are at `/admin/accounts`, `GET /api/v1/admin/accounts`, `GET /api/v1/admin/accounts/:id`,
  `POST /api/v1/admin/accounts/:id/action`, `POST /api/v1/admin/accounts/:id/unsuspend`, and
  `DELETE /api/v1/admin/accounts/:id`, and the `suspend-account`, `unsuspend-account`, and `delete-account` commands
- `/settings/delete-account`, for people to delete their own account with their password, linked from `/settings`
- `Update` activities when a local account is suspended or unsuspended, and a `Delete` when one is deleted
- Actor documents now give `/inbox` as their shared inbox, so other servers can send one copy of an activity for all
  your users instead of one each.
- Activities that another server forwards, such as replies sent on to the followers of the account being replied to, are
  now fetched from the server they came from, instead of being dropped. Each one counts against the forwarding server's
  inbox limits. See `FEDERATION.md`.
- The API documentation now describes the OAuth endpoints and the scopes each endpoint needs, and Swagger UI can sign in
  to try endpoints that need a token. See `docs/api.md`.
- The API documentation now describes every endpoint, its parameters, responses, and errors, and how it differs from
  Mastodon, with a link to Mastodon's documentation for it. It also covers the ActivityPub, WebFinger, and NodeInfo
  endpoints.
- An experimental setting for how background jobs are picked up, which may help with more than one copy of the server. See
  [Background jobs](docs/configuration.md#background-jobs).
- Profiles: a display name, a bio, fields, an avatar, and a header, edited at `/settings` or through
  `PATCH /api/v1/accounts/update_credentials`, `DELETE /api/v1/profile/avatar`, and `DELETE /api/v1/profile/header`. They're
  shown on profile pages, in the API, and in actor documents, and followers' servers get an `Update` when one changes.
  Images are converted to WebP and cropped, and animations stay animated. A field that links to a page linking back with
  `rel="me"` is shown as verified. See `docs/accounts.md`, `docs/api.md`, and `FEDERATION.md`.
- `elysium.profiles.max-fields` and `elysium.profiles.max-image-size`, for how many fields a profile can have, which
  defaults to 4, and how large an avatar or header can be, which defaults to 8 MB, and `elysium.media.directory`, for
  where uploaded images are kept. See `docs/configuration.md`.
  - **Upgrading:** uploaded images are kept on disk, and have to be backed up along with the database. With
    `compose.yaml`, they're in the new `media-data` volume, which `docker compose up -d` creates. With `elysium.service`,
    install the new file and run `systemctl daemon-reload`, and they're in `/var/lib/elysium/media`. Otherwise they go in
    `media`, in the directory the server runs from, unless you set `elysium.media.directory`.

### Changed

- Mentions from accounts the user does not follow now appear in notifications and conversations instead of the home
  timeline. Direct messages from followed accounts remain in the home timeline when addressed to the user.

- `elysium.registration.enabled` is replaced by `elysium.registration.mode`, and sign-ups without an invite now have to
  confirm an email address before they can log in.
  - **Upgrading:** the server won't start while `elysium.registration.enabled` (or `ELYSIUM_REGISTRATION_ENABLED`) is
    set, so remove it. If it was `true`, set `elysium.registration.mode=open` and an SMTP server, as in
    `docs/configuration.md`, or keep the default `closed` and send people invites. With `compose.yaml`, use the new
    `ELYSIUM_REGISTRATION_MODE` and mail settings from `.env.example`.
- Login sessions are now stored in the database. `reset-password` logs the account out of every browser straight away, with no
  restart, and so does suspending an account. Sessions survive restarts, and multiple instances no longer need sticky sessions.
  Only a hash of each session's ID is stored, so what's in the database or a backup can't be used to take over a session.
  The cookie is now `SESSION` instead of `JSESSIONID`, so everyone is logged out once when upgrading. If your proxy or load
  balancer has any rule for `JSESSIONID`, such as sticky sessions, it can be removed. Restarting no longer ends sessions, so
  run `reset-password` for an account that may be compromised.
- Other servers can now fetch public and unlisted statuses, and browsers visiting `/users/:username` get the profile page.
- Public and unlisted statuses in the API now have a `url`, which links to their page.
- Actor documents now have a `url`, and WebFinger responses now have a `http://webfinger.net/rel/profile-page` link.
- Your config now only needs the settings you want to change, and the rest come from the defaults built into the JAR.
  To switch, set `SPRING_CONFIG_ADDITIONAL_LOCATION` instead of `SPRING_CONFIG_LOCATION`, and remove any lines you
  didn't change from your `application.properties`. Lines you keep continue to override the built-in defaults. If you
  use `elysium.service`, make the same change in your installed copy.
- `elysium.service` now starts after the network and any local PostgreSQL, restarts the server if it fails, and no longer
  reports a normal stop as a failure. To switch, install the new file and run `systemctl daemon-reload`.
- `./gradlew bootJar` now builds `build/libs/elysium.jar`, with no version in the file name.
- `elysium.service` now runs the server as a dedicated `elysium` user and group, in a sandbox. To switch, move any changes
  you made to your installed copy into `systemctl edit elysium`, then run these as root, adjusting the paths if yours
  differ:
  ```shell
  useradd --system --user-group --home-dir /opt/elysium --shell /usr/sbin/nologin elysium
  chgrp elysium /etc/elysium /etc/elysium/application.properties
  chmod 750 /etc/elysium
  chmod 640 /etc/elysium/application.properties
  cp elysium.service /etc/systemd/system
  systemctl daemon-reload
  systemctl restart elysium
  ```
  Before restarting, check for anything that relied on running as root or on the old filesystem access, because the
  service will otherwise fail or lose access:
  - Ports below 1024, such as `server.port=443`. Follow the comments in the file.
  - Files only root can read, such as a `server.ssl.key-store` under `/etc/letsencrypt/live`. Give the `elysium` group
    access to them.
  - `java` or the JAR under `/home` or `/root`, such as a JDK from SDKMAN. Move them.
  - Writing outside the private `/tmp` and `/var/tmp`, such as to a log file. Follow the comments in the file.
  - Files in the home directory, such as PostgreSQL's `~/.postgresql/root.crt`. The home directory is now `/opt/elysium`,
    so move them there or set their paths in your config.