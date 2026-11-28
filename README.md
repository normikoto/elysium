# Elysium

Elysium is an experimental ActivityPub server built with Java and Spring Boot. It aims to work with Mastodon and other
common ActivityPub servers.

**Warning:** Elysium is experimental. Run it on a public server only if you are prepared to manage its limitations,
including [partial federation support](FEDERATION.md).

## Documentation
- [Installation](docs/installation.md) covers Docker Compose, systemd, and HTTPS.
- [Configuration](docs/configuration.md) covers settings, PostgreSQL, and reverse proxies.
- [Accounts and sign-ups](docs/accounts.md) covers account setup, moderation, and routine administration.
- [Using the API](docs/api.md) covers OAuth and differences from Mastodon.
- [Federation](FEDERATION.md) lists supported protocols and describes how servers interoperate.

## Local development
Run `./gradlew bootRun` to start Elysium at `http://localhost:8080` with the `dev` profile and PostgreSQL from
`compose.dev.yaml`. If you launch the app from your IDE or another tool, activate the `dev` profile yourself.

## [License](./LICENSE)
Copyright (C) 2026 Norm

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
