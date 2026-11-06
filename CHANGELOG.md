# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Initial scaffolding
- Local accounts with username and password login, hashed with Argon2id
- Optional sign-up page, turned on with `elysium.registration.enabled`
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
  `username@domain` or by URL. Following another server's user waits until that server accepts. Posts from other servers
  aren't shown yet. Followers can see a local user's followers-only posts. This adds `GET /api/v1/accounts/:id`,
  `GET /api/v1/accounts/lookup`, `GET /api/v1/accounts/relationships`, `POST /api/v1/accounts/:id/follow` and `/unfollow`,
  `GET /api/v1/accounts/:id/followers` and `/following`, and `GET /api/v2/search`. See `docs/api.md` and `FEDERATION.md`.
- New public, unlisted, and followers-only statuses are sent to followers on other servers, as a `Create`. Direct statuses
  aren't sent, and statuses from before this aren't sent. Followers-only statuses only go to those already following when
  they're posted. See `FEDERATION.md`.
- Deleting a public, unlisted, or followers-only status stops any `Create` that hasn't started sending, and sends a `Delete` to
  followers on other servers. For a followers-only status, this only goes to those already following when it was posted. A
  `Delete` waits for a `Create` that's being sent to the same server. See `FEDERATION.md`.
- A deleted public or unlisted status now tells other servers it's gone, with a `410`, rather than a 404.
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

### Changed

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