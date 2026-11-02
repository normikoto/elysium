# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Initial scaffolding
- Local accounts with username and password login, hashed with Argon2id
- Optional sign-up page, turned on with `elysium.registration.enabled`
- `elysium.bootstrap.admin-password` to set the bootstrap admin's first password
- Mastodon-compatible OAuth 2: app registration, authorization code and client credentials grants, token revocation,
  scopes, and server metadata
- `GET /api/v1/apps/verify_credentials` and `GET /api/v1/accounts/verify_credentials`
- Public API documentation, with Swagger UI at `/api-docs/ui` and the OpenAPI document at `/api-docs`
- `application.properties.example`, a starting point for your own config

### Changed

- Your config now only needs the settings you want to change, and the rest come from the defaults built into the JAR.
  To switch, set `SPRING_CONFIG_ADDITIONAL_LOCATION` instead of `SPRING_CONFIG_LOCATION`, and remove any lines you
  didn't change from your `application.properties`. Lines you keep continue to override the built-in defaults. If you
  use `elysium.service`, make the same change in your installed copy.
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