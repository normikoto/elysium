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

### Changed

- `/users/:username` now returns the actor document only to requests that ask for an ActivityPub type. Requests that
  accept HTML, including `Accept: */*` or no `Accept` header, get the profile page.
- `/users/:username` now accepts `application/ld+json` with any profile.
- `/users/:username/statuses/:id` now returns a `Note` object for public and unlisted statuses to requests that ask for an
  ActivityPub type, instead of a 406.
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