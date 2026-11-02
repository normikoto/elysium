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
- `elysium.service` now runs the server as a dedicated `elysium` user and group, in a sandbox. To switch, create the user
  and let its group read your config, as described under "Running as a service" in the README, then install the new
  file. The server can no longer write outside a private `/tmp` or see `/home`, so if it writes logs elsewhere or `java`
  or the JAR is under `/home`, follow the comments in the file or move them.